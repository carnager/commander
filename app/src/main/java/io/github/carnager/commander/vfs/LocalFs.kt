package io.github.carnager.commander.vfs

import io.github.carnager.commander.R
import android.content.Context
import android.os.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class LocalFs(private val context: Context) : Vfs {

    /** Mounted storage volumes (internal, SD cards, USB), as (label, path). */
    fun volumes(): List<Pair<String, String>> {
        val sm = context.getSystemService(StorageManager::class.java)
        return sm.storageVolumes.mapNotNull { v ->
            val dir = v.directory ?: return@mapNotNull null
            (if (v.isPrimary) context.getString(R.string.volume_internal) else v.getDescription(context)) to dir.absolutePath
        }
    }

    override fun rootOf(path: String): String =
        volumes().map { it.second }.filter { path == it || path.startsWith("$it/") }.maxByOrNull { it.length } ?: "/"

    override suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val files = File(path).listFiles() ?: throw IOException(context.getString(R.string.err_folder_not_readable, path))
        files.map { it.toEntry() }
    }

    override suspend fun stat(path: String): FileEntry? = withContext(Dispatchers.IO) {
        File(path).takeIf { it.exists() }?.toEntry()
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        if (!File(path).mkdir()) throw IOException(context.getString(R.string.err_mkdir_failed))
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        if (!File(from).renameTo(File(to))) throw IOException(context.getString(R.string.err_rename_failed))
    }

    override suspend fun deleteOne(path: String, isDir: Boolean) = withContext(Dispatchers.IO) {
        if (!File(path).delete()) throw IOException(context.getString(R.string.err_delete_failed, File(path).name))
    }

    override suspend fun deleteRecursive(path: String, isDir: Boolean) = withContext(Dispatchers.IO) {
        if (!File(path).deleteRecursively()) throw IOException(context.getString(R.string.err_delete_failed, File(path).name))
    }

    override suspend fun openRead(path: String, offset: Long): InputStream = withContext(Dispatchers.IO) {
        FileInputStream(path).also { if (offset > 0) it.channel.position(offset) }
    }

    override suspend fun openWrite(path: String): OutputStream = withContext(Dispatchers.IO) { FileOutputStream(path) }

    override suspend fun permissions(path: String): Int? = withContext(Dispatchers.IO) {
        runCatching { android.system.Os.stat(path).st_mode and 0xFFF }.getOrNull()
    }

    override suspend fun setPermissions(path: String, mode: Int) {
        withContext(Dispatchers.IO) { runCatching { android.system.Os.chmod(path, mode) } }
    }

    override suspend fun setModified(path: String, millis: Long) {
        withContext(Dispatchers.IO) { File(path).setLastModified(millis) }
    }

    private fun File.toEntry() = FileEntry(
        name = name,
        isDir = isDirectory,
        size = if (isDirectory) 0 else length(),
        modified = lastModified(),
        isLink = java.nio.file.Files.isSymbolicLink(toPath()),
    )
}
