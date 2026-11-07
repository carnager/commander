package io.github.carnager.commander

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import io.github.carnager.commander.ui.mimeOf
import io.github.carnager.commander.vfs.FileEntry
import io.github.carnager.commander.vfs.Location
import io.github.carnager.commander.vfs.Vfs
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Exposes every configured server to the system: it shows up in the file picker, files opened from
 * the app reach viewer apps as streams instead of full downloads, and other apps can save, create,
 * rename and delete there. Writes go through a local temp file that is uploaded when it is closed.
 *
 * Document ids are "<source>|<path>", so archive tabs can hand out entries the same way.
 */
class RemoteDocumentsProvider : DocumentsProvider() {
    // Providers are created before Application.onCreate, so everything app-level is looked up lazily.
    private val app get() = context!!.applicationContext as CommanderApp

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_COLUMNS)
        for (s in app.stores.servers.value) {
            c.newRow()
                .add(Root.COLUMN_ROOT_ID, s.id)
                .add(Root.COLUMN_DOCUMENT_ID, docId(Location(s.id, homeOf(s.id))))
                .add(Root.COLUMN_TITLE, s.name)
                .add(Root.COLUMN_SUMMARY, "${s.protocol.label} · ${s.host}")
                .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_CREATE)
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val loc = parse(documentId)
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        val entry = io { fs(loc).stat(loc.path) } ?: throw FileNotFoundException(documentId)
        addRow(c, loc, entry.copy(name = if (loc.path == "/" || loc.path.isEmpty()) app.vfs.displayName(loc.source) else entry.name))
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val loc = parse(parentDocumentId)
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        for (e in io { fs(loc).list(loc.path) }) {
            if (!e.hidden) addRow(c, loc.child(e.name), e)
        }
        // Lets pickers refresh themselves after a create, rename or delete.
        c.setNotificationUri(context!!.contentResolver, DocumentsContract.buildChildDocumentsUri(authority(context!!.packageName), parentDocumentId))
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = parse(parentDocumentId)
        val child = parse(documentId)
        return parent.source == child.source && (child.path + "/").startsWith(parent.path.trimEnd('/') + "/")
    }

    override fun getDocumentType(documentId: String): String {
        val loc = parse(documentId)
        val e = io { fs(loc).stat(loc.path) } ?: throw FileNotFoundException(documentId)
        return if (e.isDir) Document.MIME_TYPE_DIR else mimeOf(e.name)
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") return openForWriting(parse(documentId), mode)
        val loc = parse(documentId)
        val fs = fs(loc)
        val entry = io { fs.stat(loc.path) } ?: throw FileNotFoundException(documentId)
        val sm = context!!.getSystemService(StorageManager::class.java)
        // One thread per open file, so a slow network read never stalls other streams.
        val thread = HandlerThread("document-${loc.name}").apply { start() }
        return sm.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY, StreamingReader(fs, loc.path, entry.size, thread), Handler(thread.looper),
        )
    }

    /**
     * The writer gets a real local file; when it closes it, the content is uploaded through the
     * transfer queue (progress, foreground service, and the existing file is only replaced once the
     * upload is complete). Modes that keep existing content ("rw", "wa") start from a download.
     */
    private fun openForWriting(loc: Location, mode: String): ParcelFileDescriptor {
        if (app.vfs.isArchive(loc.source)) throw UnsupportedOperationException(Res.str(R.string.err_archive_read_only))
        val fs = fs(loc)
        val dir = java.io.File(context!!.cacheDir, "provider").apply { mkdirs() }
        val temp = java.io.File.createTempFile("save", null, dir)
        if (mode.contains('r') || mode.contains('a')) {
            io { if (fs.stat(loc.path) != null) fs.openRead(loc.path).use { input -> temp.outputStream().use { input.copyTo(it) } } }
        }
        return ParcelFileDescriptor.open(temp, ParcelFileDescriptor.parseMode(mode), closeHandler) { error ->
            if (error != null) {
                temp.delete()
                return@open
            }
            app.transfers.upload(temp, loc) {
                temp.delete()
                app.vfs.cache.remove(loc.copy(path = parentOf(loc.path)))
                notifyChildren(loc.copy(path = parentOf(loc.path)))
            }
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val dir = parse(parentDocumentId)
        val fs = fs(dir)
        if (app.vfs.isArchive(dir.source)) throw UnsupportedOperationException(Res.str(R.string.err_archive_read_only))
        val taken = io { fs.list(dir.path) }.map { it.name }.toSet()
        val isDir = mimeType == Document.MIME_TYPE_DIR
        val name = if (displayName in taken) io.github.carnager.commander.ops.freeName(displayName, isDir, taken) else displayName
        val loc = dir.child(name)
        io { if (isDir) fs.mkdir(loc.path) else fs.openWrite(loc.path).close() }
        changed(dir)
        return docId(loc)
    }

    override fun deleteDocument(documentId: String) {
        val loc = parse(documentId)
        val fs = fs(loc)
        io {
            val e = fs.stat(loc.path) ?: throw FileNotFoundException(documentId)
            fs.deleteRecursive(loc.path, e.isDir && !e.isLink)
        }
        changed(loc.copy(path = parentOf(loc.path)))
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val loc = parse(documentId)
        val target = loc.copy(path = parentOf(loc.path)).child(displayName)
        io { fs(loc).rename(loc.path, target.path) }
        changed(loc.copy(path = parentOf(loc.path)))
        return docId(target)
    }

    private fun changed(dir: Location) {
        app.vfs.cache.remove(dir)
        notifyChildren(dir)
    }

    private fun notifyChildren(dir: Location) = context!!.contentResolver.notifyChange(
        DocumentsContract.buildChildDocumentsUri(authority(context!!.packageName), docId(dir)), null,
    )

    private fun parentOf(path: String) = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

    /**
     * Serves random-access reads from a sequential stream: reads that continue where the last one
     * ended (the normal case for players) keep using the open, read-ahead stream; a seek reopens it.
     */
    private class StreamingReader(
        private val fs: Vfs,
        private val path: String,
        private val size: Long,
        private val thread: HandlerThread,
    ) : ProxyFileDescriptorCallback() {
        private var stream: InputStream? = null
        private var pos = 0L

        override fun onGetSize() = size

        override fun onRead(offset: Long, length: Int, data: ByteArray): Int {
            try {
                if (stream == null || offset != pos) {
                    stream?.close()
                    stream = runBlocking { fs.openRead(path, offset) }
                    pos = offset
                }
                val s = stream!!
                var done = 0
                while (done < length) {
                    val n = s.read(data, done, length - done)
                    if (n < 0) break
                    done += n
                }
                pos += done
                return done
            } catch (e: Exception) {
                stream = null
                throw ErrnoException("read", OsConstants.EIO)
            }
        }

        override fun onRelease() {
            runCatching { stream?.close() }
            stream = null
            thread.quitSafely()
        }
    }

    private fun addRow(c: MatrixCursor, loc: Location, e: FileEntry) {
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, docId(loc))
            .add(Document.COLUMN_DISPLAY_NAME, e.name)
            .add(Document.COLUMN_MIME_TYPE, if (e.isDir) Document.MIME_TYPE_DIR else mimeOf(e.name))
            .add(Document.COLUMN_SIZE, if (e.isDir) null else e.size)
            .add(Document.COLUMN_LAST_MODIFIED, e.modified.takeIf { it > 0 })
            .add(Document.COLUMN_FLAGS, flagsFor(loc, e))
    }

    private fun flagsFor(loc: Location, e: FileEntry): Int {
        if (app.vfs.isArchive(loc.source)) return 0
        val common = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        return if (e.isDir) common or Document.FLAG_DIR_SUPPORTS_CREATE else common or Document.FLAG_SUPPORTS_WRITE
    }

    private fun homeOf(source: String): String =
        app.stores.servers.value.firstOrNull { it.id == source }?.startPath?.ifBlank { null } ?: "/"

    private fun fs(loc: Location) = try {
        app.vfs[loc.source]
    } catch (e: Exception) {
        throw FileNotFoundException(e.message)
    }

    /** Network calls on binder threads: the framework expects these calls to block. */
    private fun <T> io(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (e: FileNotFoundException) {
        throw e
    } catch (e: Exception) {
        throw FileNotFoundException(e.message)
    }

    companion object {
        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON, Root.COLUMN_FLAGS,
        )
        private val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        /** Upload hand-off after a writer closes its file; one quiet thread is plenty. */
        private val closeHandler by lazy { Handler(HandlerThread("document-close").apply { start() }.looper) }

        fun authority(packageName: String) = "$packageName.documents"

        fun docId(loc: Location) = "${loc.source}|${loc.path}"

        fun parse(id: String): Location {
            val source = id.substringBefore('|')
            val path = id.substringAfter('|', "/").ifEmpty { "/" }
            return Location(source, path)
        }

        fun uriFor(packageName: String, loc: Location) =
            DocumentsContract.buildDocumentUri(authority(packageName), docId(loc))

        fun notifyRootsChanged(context: android.content.Context) =
            context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority(context.packageName)), null)
    }
}
