package io.github.carnager.commander.vfs

import io.github.carnager.commander.R
import io.github.carnager.commander.Res
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/** A directory or file somewhere: on the device ([LOCAL]) or on a configured server (its id). */
@Serializable
data class Location(val source: String, val path: String) {
    val isLocal get() = source == LOCAL
    val name get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

    fun child(name: String) = copy(path = if (path.endsWith("/")) path + name else "$path/$name")

    companion object {
        const val LOCAL = "local"
    }
}

fun parentPath(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

data class FileEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    /** Epoch millis, 0 when unknown. */
    val modified: Long,
    val isLink: Boolean = false,
    /** Hidden by file attribute (SMB), on top of the dot-file convention. */
    val hiddenAttr: Boolean = false,
) {
    val hidden get() = hiddenAttr || name.startsWith(".")
}

/** One file system: the device storage or one server. Paths are absolute, '/'-separated. */
interface Vfs {
    /** Topmost path navigation may go up to from [path]. */
    fun rootOf(path: String): String = "/"

    suspend fun list(path: String): List<FileEntry>
    suspend fun stat(path: String): FileEntry?
    suspend fun mkdir(path: String)
    suspend fun rename(from: String, to: String)

    /** Whether [rename] can move [from] to [to]; otherwise moves fall back to copy + delete. */
    fun canRename(from: String, to: String): Boolean = true

    /** Deletes a file or an empty directory. */
    suspend fun deleteOne(path: String, isDir: Boolean)

    /** Reads from [offset] on; streaming consumers (video players) seek with this. */
    suspend fun openRead(path: String, offset: Long = 0): InputStream
    suspend fun openWrite(path: String): OutputStream

    /** Sets the modification time; best effort, file systems that cannot do it ignore it. */
    suspend fun setModified(path: String, millis: Long) {}

    /** Unix permission bits (0o7777), or null where the file system has none (SMB, archives). */
    suspend fun permissions(path: String): Int? = null

    suspend fun setPermissions(path: String, mode: Int) {}

    suspend fun deleteRecursive(path: String, isDir: Boolean) {
        if (isDir) for (e in list(path)) deleteRecursive("$path/${e.name}".replace("//", "/"), e.isDir && !e.isLink)
        deleteOne(path, isDir)
    }
}

/** Thrown when a server presents a host key we have not seen; the UI asks the user to trust it. */
class UnknownHostKeyException(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val keyBlob: String,
) : java.io.IOException(Res.str(R.string.err_unknown_host_key, host))

/** Thrown when a server's host key differs from the remembered one. Never overridable silently. */
class HostKeyMismatchException(host: String, fingerprint: String) :
    java.io.IOException(Res.str(R.string.err_host_key_changed, host, fingerprint))
