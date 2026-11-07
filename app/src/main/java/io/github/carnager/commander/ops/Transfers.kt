package io.github.carnager.commander.ops

import io.github.carnager.commander.R
import io.github.carnager.commander.Res
import io.github.carnager.commander.vfs.FileEntry
import io.github.carnager.commander.vfs.Location
import io.github.carnager.commander.vfs.Vfs
import io.github.carnager.commander.vfs.VfsRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import kotlin.coroutines.coroutineContext

data class TransferState(
    val title: String,
    val current: String = "",
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    /** Operations waiting behind this one. */
    val queued: Int = 0,
) {
    val progress get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else null
}

enum class Conflict { OVERWRITE, SKIP, KEEP_BOTH }

/** "photo.jpg" → "photo (2).jpg", the first name not in [taken]. */
fun freeName(name: String, isDir: Boolean, taken: Set<String>): String {
    val dot = name.lastIndexOf('.').takeIf { it > 0 && !isDir } ?: name.length
    var n = 2
    while (true) {
        val candidate = name.substring(0, dot) + " ($n)" + name.substring(dot)
        if (candidate !in taken) return candidate
        n++
    }
}

enum class ArchiveFormat(val label: String, val extension: String) {
    ZIP("ZIP", ".zip"), TAR_GZ("TAR.GZ", ".tar.gz"), SEVEN_Z("7Z", ".7z"),
}

/** Runs one file operation at a time in the app scope, so it survives tab and screen changes. */
class Transfers(
    private val vfs: VfsRegistry,
    private val scope: CoroutineScope,
    /** Scratch space for archives being built. */
    private val tempDir: File,
    val trash: Trash,
    /** Called when an operation starts; the app uses it to go foreground. */
    private val onStart: () -> Unit = {},
    /** Device paths an operation created or removed, so the media index can catch up. */
    private val onLocalChange: (List<String>) -> Unit = {},
) {
    /** Paths touched by the running operation; reported when it ends, successful or not. */
    private val touched = mutableListOf<String>()

    private fun touch(loc: Location) {
        if (loc.isLocal) synchronized(touched) { touched += loc.path }
    }

    private val _state = MutableStateFlow<TransferState?>(null)
    val state: StateFlow<TransferState?> = _state
    private var job: Job? = null

    private class Op(val title: String, val onDone: (Throwable?) -> Unit, val block: suspend () -> Unit)

    /** Operations run one after another; new ones wait here instead of being refused. */
    private val queue = ArrayDeque<Op>()

    fun cancel() {
        job?.cancel()
    }

    /**
     * Copies (or moves) [items] from [from] into [to]. Moves within one file system are plain renames.
     * Copying out of an archive tab is how extraction works. [onDone] gets the error, if any.
     */
    fun copy(
        from: Location,
        items: List<FileEntry>,
        to: Location,
        move: Boolean,
        conflict: Conflict,
        onDone: (Throwable?) -> Unit,
    ) = run(Res.str(if (move) R.string.op_move else R.string.op_copy), onDone) {
        val src = vfs[from.source]
        val dst = vfs[to.source]
        if (from.source == to.source) {
            if (from.path == to.path) throw IOException(Res.str(R.string.err_same_folder))
            if (items.any { to.path.startsWith(from.child(it.name).path + "/") || to.path == from.child(it.name).path })
                throw IOException(Res.str(R.string.err_copy_into_itself))
        }
        val existing = dst.list(to.path).map { it.name }.toSet()
        val todo = items.filter { conflict != Conflict.SKIP || it.name !in existing }
        // Where each top-level item lands; only "keep both" changes names.
        val taken = existing.toMutableSet()
        val targetName = todo.associate { e ->
            val name = if (conflict == Conflict.KEEP_BOTH && e.name in existing) freeName(e.name, e.isDir, taken) else e.name
            taken += name
            e.name to name
        }
        fun targetOf(rel: String) = to.child(targetName.getValue(rel.substringBefore('/')) + rel.removePrefix(rel.substringBefore('/'))).path
        todo.forEach { e ->
            touch(to.child(targetName.getValue(e.name)))
            if (move) touch(from.child(e.name))
        }

        if (move && from.source == to.source && todo.all { src.canRename(from.child(it.name).path, to.child(it.name).path) }) {
            for (e in todo) {
                update { it.copy(current = e.name) }
                val target = targetOf(e.name)
                if (targetName.getValue(e.name) !in existing) {
                    src.rename(from.child(e.name).path, target)
                    continue
                }
                // Park the old target first, so a failed rename can be rolled back.
                val old = "$target.commander-old"
                src.rename(target, old)
                try {
                    src.rename(from.child(e.name).path, target)
                } catch (t: Throwable) {
                    withContext(NonCancellable) { runCatching { src.rename(old, target) } }
                    throw t
                }
                src.deleteRecursive(old, e.isDir)
            }
            return@run
        }

        val files = scan(src, from, todo)
        for ((rel, e) in files) {
            coroutineContext.ensureActive()
            val target = targetOf(rel)
            if (e.isDir) {
                if (dst.stat(target)?.isDir != true) dst.mkdir(target)
                continue
            }
            update { it.copy(current = rel) }
            writeSafely(dst, target) { out -> src.openRead(from.child(rel).path).use { pump(it, out) } }
            if (e.modified > 0) runCatching { dst.setModified(target, e.modified) }
            update { it.copy(filesDone = it.filesDone + 1) }
        }
        // Folder times last, deepest first: writing their contents has just bumped them.
        for ((rel, e) in files.asReversed()) {
            if (e.isDir && e.modified > 0) runCatching { dst.setModified(targetOf(rel), e.modified) }
        }
        if (move) for (e in todo) src.deleteRecursive(from.child(e.name).path, e.isDir)
    }

    /** Packs [items] from [from] into a new archive [name] inside [to]. Sources may be remote. */
    fun compress(
        from: Location,
        items: List<FileEntry>,
        to: Location,
        name: String,
        format: ArchiveFormat,
        /** Encrypts with AES-256 (ZIP and 7z); null for an open archive. */
        password: CharArray?,
        onDone: (Throwable?) -> Unit,
    ) = run(Res.str(R.string.op_compress), onDone) {
        val src = vfs[from.source]
        val dst = vfs[to.source]
        if (dst.stat(to.child(name).path) != null) throw IOException(Res.str(R.string.err_already_exists, name))
        touch(to.child(name))
        val files = scan(src, from, items)

        suspend fun read(rel: String, body: (InputStream) -> Unit) {
            coroutineContext.ensureActive()
            update { it.copy(current = rel) }
            src.openRead(from.child(rel).path).use(body)
            update { it.copy(filesDone = it.filesDone + 1) }
        }

        tempDir.mkdirs()
        val temp = File.createTempFile("pack", format.extension, tempDir)
        try {
            when {
                format == ArchiveFormat.ZIP && password != null ->
                    net.lingala.zip4j.io.outputstream.ZipOutputStream(temp.outputStream().buffered(), password).use { zip ->
                        for ((rel, e) in files) {
                            val params = net.lingala.zip4j.model.ZipParameters().apply {
                                fileNameInZip = if (e.isDir) "$rel/" else rel
                                if (e.modified > 0) lastModifiedFileTime = e.modified
                                // Folders carry no data to protect, and zip4j refuses to encrypt them.
                                isEncryptFiles = !e.isDir
                                encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
                                aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
                            }
                            zip.putNextEntry(params)
                            if (!e.isDir) read(rel) { pump(it, zip) }
                            zip.closeEntry()
                        }
                    }
                format == ArchiveFormat.ZIP -> ZipArchiveOutputStream(temp).use { zip ->
                    for ((rel, e) in files) {
                        zip.putArchiveEntry(ZipArchiveEntry(if (e.isDir) "$rel/" else rel).apply { if (e.modified > 0) time = e.modified })
                        if (!e.isDir) read(rel) { pump(it, zip) }
                        zip.closeArchiveEntry()
                    }
                }
                format == ArchiveFormat.TAR_GZ -> TarArchiveOutputStream(GzipCompressorOutputStream(temp.outputStream().buffered())).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    for ((rel, e) in files) {
                        tar.putArchiveEntry(TarArchiveEntry(if (e.isDir) "$rel/" else rel).apply {
                            if (!e.isDir) size = e.size
                            if (e.modified > 0) modTime = Date(e.modified)
                        })
                        if (!e.isDir) read(rel) { pump(it, tar) }
                        tar.closeArchiveEntry()
                    }
                }
                else -> (if (password != null) SevenZOutputFile(temp, password) else SevenZOutputFile(temp)).use { sz ->
                    for ((rel, e) in files) {
                        sz.putArchiveEntry(SevenZArchiveEntry().apply {
                            this.name = rel
                            isDirectory = e.isDir
                            if (e.modified > 0) lastModifiedDate = Date(e.modified)
                        })
                        if (!e.isDir) read(rel) { input -> pump(input) { b, n -> sz.write(b, 0, n) } }
                        sz.closeArchiveEntry()
                    }
                }
            }
            update { it.copy(current = name, bytesDone = 0, bytesTotal = temp.length()) }
            writeSafely(dst, to.child(name).path) { out -> temp.inputStream().use { pump(it, out) } }
        } finally {
            temp.delete()
        }
    }

    /** Deletes for good. Inside a trash this also drops the items' trash records. */
    /** Something another app shared with us: a name, a size if known, and a way to read it. */
    class Incoming(val name: String, val size: Long, val open: () -> InputStream)

    /** Saves shared items into [to]; names already taken get a " (2)" suffix rather than replacing anything. */
    fun receive(items: List<Incoming>, to: Location, onDone: (Throwable?) -> Unit) = run(Res.str(R.string.op_save), onDone) {
        val dst = vfs[to.source]
        val taken = dst.list(to.path).map { it.name }.toMutableSet()
        update { it.copy(filesTotal = items.size, bytesTotal = items.sumOf { i -> i.size.coerceAtLeast(0) }) }
        for (item in items) {
            coroutineContext.ensureActive()
            val name = if (item.name in taken) freeName(item.name, false, taken) else item.name
            taken += name
            touch(to.child(name))
            update { it.copy(current = name) }
            writeSafely(dst, to.child(name).path) { out -> item.open().use { pump(it, out) } }
            update { it.copy(filesDone = it.filesDone + 1) }
        }
    }

    /** Puts a finished local file at [target], replacing what is there (a save from another app). */
    fun upload(file: File, target: Location, onDone: (Throwable?) -> Unit) = run(Res.str(R.string.op_save), onDone) {
        update { it.copy(current = target.name, bytesTotal = file.length(), filesTotal = 1) }
        writeSafely(vfs[target.source], target.path) { out -> file.inputStream().use { pump(it, out) } }
    }

    fun delete(from: Location, items: List<FileEntry>, onDone: (Throwable?) -> Unit) = run(Res.str(R.string.op_delete), onDone) {
        val fs = vfs[from.source]
        val inTrash = trash.isTrashFiles(from)
        each(items) { e ->
            touch(from.child(e.name))
            if (inTrash) trash.purge(from, e) else fs.deleteRecursive(from.child(e.name).path, e.isDir && !e.isLink)
        }
    }

    /** [onTrashed] learns where everything went (trash files folder, entries as named there), for undo. */
    fun moveToTrash(
        from: Location,
        items: List<FileEntry>,
        onTrashed: (Location, List<FileEntry>) -> Unit,
        onDone: (Throwable?) -> Unit,
    ) = run(Res.str(R.string.op_trash), onDone) {
        val root = trash.rootFor(from) ?: throw IOException(Res.str(R.string.err_no_trash_here))
        val moved = mutableListOf<FileEntry>()
        try {
            each(items) { e ->
                touch(from.child(e.name))
                moved += e.copy(name = trash.moveToTrash(from, e, root))
            }
        } finally {
            onTrashed(root.child("files"), moved)
        }
    }

    /** Counts files, folders and bytes below [items], reporting the running totals as it goes. */
    suspend fun measure(from: Location, items: List<FileEntry>, onProgress: (files: Int, dirs: Int, bytes: Long) -> Unit) {
        val fs = vfs[from.source]
        var files = 0
        var dirs = 0
        var bytes = 0L
        var reported = 0L
        suspend fun walk(path: String, e: FileEntry) {
            coroutineContext.ensureActive()
            if (e.isDir && !e.isLink) {
                dirs++
                for (c in runCatching { fs.list(path) }.getOrDefault(emptyList())) walk("$path/${c.name}", c)
            } else {
                files++
                bytes += e.size
            }
            val now = System.currentTimeMillis()
            if (now - reported > 200) {
                reported = now
                onProgress(files, dirs, bytes)
            }
        }
        for (e in items) walk(from.child(e.name).path, e)
        onProgress(files, dirs, bytes)
    }

    fun restore(files: Location, items: List<FileEntry>, onDone: (Throwable?) -> Unit) = run(Res.str(R.string.op_restore), onDone) {
        each(items) { e -> touch(files.copy(path = trash.restore(files, e.name))) }
    }

    private suspend fun each(items: List<FileEntry>, action: suspend (FileEntry) -> Unit) {
        update { it.copy(filesTotal = items.size) }
        for (e in items) {
            coroutineContext.ensureActive()
            update { it.copy(current = e.name) }
            action(e)
            update { it.copy(filesDone = it.filesDone + 1) }
        }
    }

    /** Fetches a remote file into [target] (for handing it to a viewer app or opening an archive). */
    fun download(from: Location, size: Long, target: File, onDone: (Throwable?) -> Unit) =
        run(Res.str(R.string.op_download, from.name), onDone, urgent = true) {
            update { it.copy(current = from.name, bytesTotal = size, filesTotal = 1) }
            target.parentFile?.mkdirs()
            vfs[from.source].openRead(from.path).use { input -> target.outputStream().use { pump(input, it) } }
        }

    /** Lists everything below [items] (parents before children) and sets the progress totals. */
    private suspend fun scan(src: Vfs, from: Location, items: List<FileEntry>): List<Pair<String, FileEntry>> {
        val files = mutableListOf<Pair<String, FileEntry>>() // relative path → entry
        suspend fun walk(rel: String, e: FileEntry) {
            coroutineContext.ensureActive()
            files += rel to e
            if (e.isDir) for (c in src.list(from.child(rel).path)) walk("$rel/${c.name}", c)
        }
        for (e in items) walk(e.name, e)
        update { it.copy(bytesTotal = files.sumOf { f -> f.second.size }, filesTotal = files.count { f -> !f.second.isDir }) }
        return files
    }

    /**
     * Writes next to [target] and swaps at the end, so a failed or cancelled write never destroys
     * an existing file it was meant to replace. A replaced file's permissions carry over (an
     * edited script stays executable).
     */
    private suspend fun writeSafely(dst: Vfs, target: String, body: suspend (OutputStream) -> Unit) {
        val part = "$target.commander-part"
        try {
            dst.openWrite(part).use { body(it) }
            if (dst.stat(target) != null) {
                runCatching { dst.permissions(target) }.getOrNull()?.let { mode -> runCatching { dst.setPermissions(part, mode) } }
                dst.deleteOne(target, false)
            }
            dst.rename(part, target)
        } catch (t: Throwable) {
            withContext(NonCancellable) { runCatching { dst.deleteOne(part, false) } }
            throw t
        }
    }

    private fun pump(input: InputStream, output: OutputStream) = pump(input) { b, n -> output.write(b, 0, n) }

    /** Copies [input] in chunks, counting progress and stopping promptly when cancelled. */
    private fun pump(input: InputStream, sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(BUFFER)
        while (true) {
            if (job?.isCancelled == true) throw CancellationException()
            val n = input.read(buf)
            if (n < 0) break
            sink(buf, n)
            _state.value = _state.value?.let { it.copy(bytesDone = it.bytesDone + n) }
        }
    }

    private fun update(f: (TransferState) -> TransferState) {
        _state.value = _state.value?.let(f)
    }

    private fun run(title: String, onDone: (Throwable?) -> Unit, urgent: Boolean = false, block: suspend () -> Unit) {
        synchronized(queue) {
            val op = Op(title, onDone, block)
            if (urgent) queue.addFirst(op) else queue.addLast(op)
            if (job?.isActive == true) {
                _state.value = _state.value?.copy(queued = queue.size)
                return
            }
        }
        onStart()
        next()
    }

    private fun next() {
        val op = synchronized(queue) {
            val op = queue.removeFirstOrNull()
            _state.value = op?.let { TransferState(it.title, queued = queue.size) }
            op
        } ?: return
        job = scope.launch(Dispatchers.IO) {
            val error = runCatching { op.block() }.exceptionOrNull()
            val changed = synchronized(touched) { touched.toList().also { touched.clear() } }
            if (changed.isNotEmpty()) onLocalChange(changed)
            if (error != null && error !is CancellationException) android.util.Log.w("Transfers", op.title, error)
            op.onDone(error)
            next()
        }
    }

    private companion object {
        const val BUFFER = 256 * 1024
    }
}
