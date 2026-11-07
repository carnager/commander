package io.github.carnager.commander.vfs

import io.github.carnager.commander.R
import io.github.carnager.commander.Res
import com.github.junrar.Archive
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedRarVersionException
import com.github.junrar.exception.WrongPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Archive formats we can browse, recognised by file name. */
enum class ArchiveKind { ZIP, SEVEN_Z, RAR, TAR, COMPRESSED_TAR, SINGLE_COMPRESSED }

fun archiveKindOf(name: String): ArchiveKind? {
    val n = name.lowercase()
    return when {
        n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".cbz") -> ArchiveKind.ZIP
        n.endsWith(".7z") -> ArchiveKind.SEVEN_Z
        n.endsWith(".rar") || n.endsWith(".cbr") -> ArchiveKind.RAR
        n.endsWith(".tar") -> ArchiveKind.TAR
        listOf(".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz").any { n.endsWith(it) } -> ArchiveKind.COMPRESSED_TAR
        n.endsWith(".gz") || n.endsWith(".bz2") || n.endsWith(".xz") -> ArchiveKind.SINGLE_COMPRESSED
        else -> null
    }
}

/** The archive needs a password ([wrong]: the one given did not fit). The UI asks and retries. */
class ArchivePasswordException(val archiveId: String, val archive: String, val wrong: Boolean) :
    IOException(Res.str(if (wrong) R.string.err_archive_wrong_password else R.string.err_archive_password_protected, archive))

/**
 * Read-only view of an archive file on the device. Paths are "/dir/file" inside the archive.
 * Copying out of it (to another tab) is how files get extracted.
 *
 * Zip is read in place. Tar, 7z and RAR (solid formats, where every entry would decompress from the
 * start) are unpacked once into [scratch] on first read; repeated reads then cost nothing.
 * Encrypted archives refuse to open until [password] is set, so the user is asked up front rather
 * than on the first file that happens to be opened.
 */
class ArchiveFs(val file: File, val displayName: String, private val scratch: File, private val id: String) : Vfs {
    private val kind = archiveKindOf(displayName) ?: throw IOException(Res.str(R.string.err_archive_unknown_format))
    private var index: Map<String, List<FileEntry>>? = null
    private var unpacked = false
    private var encrypted = false

    @Volatile
    var password: CharArray? = null
        set(value) {
            field = value
            synchronized(this) {
                index = null
                unpacked = false
            }
        }

    override suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        index()[norm(path)] ?: throw IOException(Res.str(R.string.err_archive_no_folder, path))
    }

    override suspend fun stat(path: String): FileEntry? = withContext(Dispatchers.IO) {
        val p = norm(path)
        if (p == "/") return@withContext FileEntry("/", true, 0, 0)
        index()[parentPath(p)]?.firstOrNull { it.name == p.substringAfterLast('/') }
    }

    override suspend fun openRead(path: String, offset: Long): InputStream = withContext(Dispatchers.IO) {
        index()
        val p = norm(path).removePrefix("/")
        val stream = when (kind) {
            ArchiveKind.ZIP -> {
                val zip = zip()
                val header = zip.getFileHeader(p) ?: run { zip.close(); throw IOException(Res.str(R.string.err_archive_no_entry, p)) }
                val input = try {
                    zip.getInputStream(header)
                } catch (e: ZipException) {
                    zip.close()
                    throw if (e.type == ZipException.Type.WRONG_PASSWORD) ArchivePasswordException(id, displayName, true) else e
                }
                // zip4j reports a wrong password only once reading starts; translate it there too.
                object : FilterInputStream(input) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int = guard { super.read(b, off, len) }
                    override fun read(): Int = guard { super.read() }
                    override fun close() {
                        try { super.close() } finally { zip.close() }
                    }
                }
            }
            else -> {
                unpackOnce()
                File(scratch, p).inputStream()
            }
        }
        stream.also { if (offset > 0) it.skipNBytes(offset) }
    }

    override suspend fun mkdir(path: String) = readOnly()
    override suspend fun rename(from: String, to: String) = readOnly()
    override suspend fun deleteOne(path: String, isDir: Boolean) = readOnly()
    override suspend fun openWrite(path: String): OutputStream = readOnly()

    private fun readOnly(): Nothing = throw IOException(Res.str(R.string.err_archive_read_only))

    private fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: ZipException) {
        throw if (e.type == ZipException.Type.WRONG_PASSWORD) ArchivePasswordException(id, displayName, true) else e
    }

    private fun zip() = ZipFile(file, password)

    @Synchronized
    private fun index(): Map<String, List<FileEntry>> {
        index?.let { return it }
        val entries = mutableListOf<FileEntry>() // name = full path inside the archive
        try {
            when (kind) {
                ArchiveKind.ZIP -> zip().use { zip ->
                    for (h in zip.fileHeaders) {
                        if (h.isEncrypted) encrypted = true
                        entries += FileEntry(h.fileName, h.isDirectory, if (h.isDirectory) 0 else h.uncompressedSize, h.lastModifiedTimeEpoch)
                    }
                }
                ArchiveKind.SEVEN_Z -> sevenZ().use { sz ->
                    for (e in sz.entries) {
                        if (e.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true) encrypted = true
                        entries += FileEntry(e.name, e.isDirectory, e.size, if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0)
                    }
                }
                ArchiveKind.RAR -> rar().use { rar ->
                    for (h in rar.fileHeaders) {
                        if (h.isEncrypted) encrypted = true
                        entries += FileEntry(h.fileName.replace('\\', '/'), h.isDirectory, h.fullUnpackSize, h.mTime?.time ?: 0)
                    }
                }
                ArchiveKind.SINGLE_COMPRESSED -> {
                    val inner = displayName.substringBeforeLast('.')
                    entries += FileEntry(inner, false, 0, file.lastModified())
                }
                else -> tarStream().use { tar ->
                    while (true) {
                        val e = tar.nextEntry ?: break
                        entries += e.toFull()
                    }
                }
            }
        } catch (e: PasswordRequiredException) {
            // 7z with encrypted headers: not even the listing is readable without the password.
            throw ArchivePasswordException(id, displayName, password != null)
        } catch (e: UnsupportedRarVersionException) {
            throw IOException(Res.str(R.string.err_rar5_unsupported))
        } catch (e: WrongPasswordException) {
            throw ArchivePasswordException(id, displayName, password != null)
        } catch (e: RarException) {
            throw IOException(e.message ?: Res.str(R.string.err_rar_broken), e)
        } catch (e: IOException) {
            if (kind == ArchiveKind.SEVEN_Z && password != null) throw ArchivePasswordException(id, displayName, true)
            throw e
        }
        if (encrypted && password == null) throw ArchivePasswordException(id, displayName, false)
        return buildTree(entries).also { index = it }
    }

    /** Turns flat full paths into directory listings, inventing directories archives often omit. */
    private fun buildTree(flat: List<FileEntry>): Map<String, List<FileEntry>> {
        val dirs = linkedMapOf<String, MutableMap<String, FileEntry>>("/" to linkedMapOf())
        fun ensureDir(path: String) {
            if (path in dirs) return
            val parent = parentPath(path)
            ensureDir(parent)
            dirs[path] = linkedMapOf()
            dirs.getValue(parent).putIfAbsent(path.substringAfterLast('/'), FileEntry(path.substringAfterLast('/'), true, 0, 0))
        }
        for (e in flat) {
            val full = norm(e.name)
            if (full == "/") continue
            val parent = parentPath(full)
            ensureDir(parent)
            val name = full.substringAfterLast('/')
            if (e.isDir) ensureDir(full)
            dirs.getValue(parent)[name] = e.copy(name = name)
        }
        return dirs.mapValues { it.value.values.toList() }
    }

    @Synchronized
    private fun unpackOnce() {
        if (unpacked) return
        scratch.deleteRecursively()
        scratch.mkdirs()
        val root = scratch.canonicalPath + File.separator
        fun target(name: String): File? {
            val out = File(scratch, name)
            // Refuse entries like "../../x" that would escape the scratch directory.
            return out.takeIf { it.canonicalPath.startsWith(root) }
        }
        fun extract(name: String, isDir: Boolean, data: InputStream) {
            val out = target(name) ?: return
            if (isDir) out.mkdirs()
            else {
                out.parentFile?.mkdirs()
                out.outputStream().use { data.copyTo(it) }
            }
        }
        try {
            when (kind) {
                ArchiveKind.SINGLE_COMPRESSED -> decompressed().use { extract(displayName.substringBeforeLast('.'), false, it) }
                ArchiveKind.SEVEN_Z -> sevenZ().use { sz ->
                    // Sequential reads continue the current solid block instead of restarting it.
                    val current = object : InputStream() {
                        override fun read() = sz.read()
                        override fun read(b: ByteArray, off: Int, len: Int) = sz.read(b, off, len)
                    }
                    while (true) {
                        val e = sz.nextEntry ?: break
                        extract(e.name, e.isDirectory, current)
                    }
                }
                ArchiveKind.RAR -> rar().use { rar ->
                    for (h in rar.fileHeaders) {
                        val out = target(h.fileName.replace('\\', '/')) ?: continue
                        if (h.isDirectory) out.mkdirs()
                        else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { rar.extractFile(h, it) }
                        }
                    }
                }
                else -> tarStream().use { tar ->
                    while (true) {
                        val e = tar.nextEntry ?: break
                        extract(e.name, e.isDirectory, tar)
                    }
                }
            }
        } catch (e: PasswordRequiredException) {
            throw ArchivePasswordException(id, displayName, password != null)
        } catch (e: IOException) {
            // A wrong key shows up as corrupt data (bad CRC, broken stream) in 7z and RAR.
            if (encrypted) throw ArchivePasswordException(id, displayName, true)
            throw e
        } catch (e: RarException) {
            if (encrypted || e is WrongPasswordException) throw ArchivePasswordException(id, displayName, true)
            throw IOException(e.message, e)
        }
        unpacked = true
    }

    private fun sevenZ(): SevenZFile = SevenZFile.builder().setFile(file).apply { password?.let { setPassword(it) } }.get()

    private fun rar(): Archive = Archive(file, password?.let { String(it) })

    private fun decompressed(): InputStream =
        CompressorStreamFactory().createCompressorInputStream(BufferedInputStream(file.inputStream()))

    private fun tarStream(): ArchiveInputStream<*> =
        TarArchiveInputStream(if (kind == ArchiveKind.TAR) BufferedInputStream(file.inputStream()) else decompressed())

    private fun ArchiveEntry.toFull() = FileEntry(name, isDirectory, if (isDirectory) 0 else size, lastModifiedDate?.time ?: 0)

    private fun norm(path: String): String = "/" + path.trim('/')
}
