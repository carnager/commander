package io.github.carnager.commander.ops

import io.github.carnager.commander.R
import io.github.carnager.commander.Res
import android.net.Uri
import io.github.carnager.commander.vfs.FileEntry
import io.github.carnager.commander.vfs.Location
import io.github.carnager.commander.vfs.SftpFs
import io.github.carnager.commander.vfs.Vfs
import io.github.carnager.commander.vfs.VfsRegistry
import io.github.carnager.commander.vfs.parentPath
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trash in the freedesktop.org layout: `<root>/files` holds the items, `<root>/info/<name>.trashinfo`
 * records where each came from and when. On an SFTP server this is the user's real desktop trash
 * (~/.local/share/Trash), so things deleted from the phone show up in the Linux file manager.
 * Moving into the trash is a rename, so it is instant and needs no extra space.
 */
class Trash(private val vfs: VfsRegistry) {

    /** The trash for items in [dir], or null where there is none (SMB, archives, outside an SFTP home). */
    suspend fun rootFor(dir: Location): Location? {
        if (dir.isLocal) {
            val volume = vfs.local.rootOf(dir.path)
            return if (volume == "/") null else Location(Location.LOCAL, "$volume/$LOCAL_DIR")
        }
        val fs = vfs[dir.source] as? SftpFs ?: return null
        val home = fs.homeDir()
        if (dir.path != home && !dir.path.startsWith("$home/")) return null
        return Location(dir.source, "$home/$XDG_DIR")
    }

    /** Whether [loc] is the "files" folder of a trash, where restore and purge apply. */
    fun isTrashFiles(loc: Location) = loc.path.endsWith("/$LOCAL_DIR/files") || loc.path.endsWith("/$XDG_DIR/files")

    /** The folder a trash belongs to (volume root or home), for leaving the trash view upwards. */
    fun ownerOf(trashFiles: Location): Location = trashFiles.copy(
        path = trashFiles.path.removeSuffix("/$LOCAL_DIR/files").removeSuffix("/$XDG_DIR/files").ifEmpty { "/" }
    )

    /** Returns the name the item got inside the trash (it may differ when that name was taken). */
    suspend fun moveToTrash(dir: Location, e: FileEntry, root: Location): String {
        val fs = vfs[dir.source]
        ensureDirs(fs, root.child("files").path)
        ensureDirs(fs, root.child("info").path)
        if (root.isLocal && fs.stat(root.child(".nomedia").path) == null) {
            // Keeps trashed photos out of gallery apps.
            fs.openWrite(root.child(".nomedia").path).close()
        }
        val taken = fs.list(root.child("files").path).map { it.name }.toSet()
        var name = e.name
        var n = 2
        while (name in taken) {
            val dot = e.name.lastIndexOf('.').takeIf { it > 0 && !e.isDir } ?: e.name.length
            name = e.name.substring(0, dot) + ".$n" + e.name.substring(dot)
            n++
        }
        val info = root.child("info").child("$name.trashinfo").path
        fs.openWrite(info).use { it.write(infoText(dir.child(e.name).path).toByteArray()) }
        try {
            fs.rename(dir.child(e.name).path, root.child("files").child(name).path)
        } catch (t: Throwable) {
            runCatching { fs.deleteOne(info, false) }
            throw t
        }
        return name
    }

    /** Puts [name] from the trash files folder [files] back where it was deleted from; returns that path. */
    suspend fun restore(files: Location, name: String): String {
        val fs = vfs[files.source]
        val root = files.copy(path = parentPath(files.path))
        val info = root.child("info").child("$name.trashinfo").path
        val original = runCatching { fs.openRead(info).use { parseOriginal(it.readBytes().decodeToString()) } }.getOrNull()
            ?: throw IOException(Res.str(R.string.err_trash_origin_unknown, name))
        if (fs.stat(original) != null) throw IOException(Res.str(R.string.err_trash_origin_exists, original.substringAfterLast('/')))
        ensureDirs(fs, parentPath(original))
        fs.rename(files.child(name).path, original)
        runCatching { fs.deleteOne(info, false) }
        return original
    }

    /** Deletes [e] from the trash files folder for good, along with its record. */
    suspend fun purge(files: Location, e: FileEntry) {
        val fs = vfs[files.source]
        fs.deleteRecursive(files.child(e.name).path, e.isDir && !e.isLink)
        runCatching { fs.deleteOne(files.copy(path = parentPath(files.path)).child("info").child("${e.name}.trashinfo").path, false) }
    }

    /** Empties out everything trashed more than [days] ago; used for the device trash on start. */
    suspend fun purgeOlderThan(root: Location, days: Int) {
        val fs = vfs[root.source]
        val files = root.child("files")
        val entries = runCatching { fs.list(files.path) }.getOrNull() ?: return
        val cutoff = System.currentTimeMillis() - days * 24L * 3600 * 1000
        for (e in entries) {
            val info = root.child("info").child("${e.name}.trashinfo").path
            val deleted = runCatching { fs.openRead(info).use { parseDate(it.readBytes().decodeToString()) } }.getOrNull() ?: e.modified
            if (deleted in 1 until cutoff) runCatching { purge(files, e) }
        }
    }

    private suspend fun ensureDirs(fs: Vfs, path: String) {
        if (fs.stat(path)?.isDir == true) return
        ensureDirs(fs, parentPath(path))
        fs.mkdir(path)
    }

    private fun infoText(path: String) = buildString {
        append("[Trash Info]\n")
        append("Path=").append(path.split('/').joinToString("/") { Uri.encode(it) }).append('\n')
        append("DeletionDate=").append(SimpleDateFormat(DATE, Locale.ROOT).format(Date())).append('\n')
    }

    private fun parseOriginal(text: String) =
        text.lineSequence().firstOrNull { it.startsWith("Path=") }?.removePrefix("Path=")?.let { Uri.decode(it) }

    private fun parseDate(text: String) =
        text.lineSequence().firstOrNull { it.startsWith("DeletionDate=") }?.removePrefix("DeletionDate=")
            ?.let { SimpleDateFormat(DATE, Locale.ROOT).parse(it)?.time }

    companion object {
        const val LOCAL_DIR = ".Trash-commander"
        const val XDG_DIR = ".local/share/Trash"
        private const val DATE = "yyyy-MM-dd'T'HH:mm:ss"
    }
}
