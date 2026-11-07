package io.github.carnager.commander.vfs

import io.github.carnager.commander.R
import io.github.carnager.commander.Res
import io.github.carnager.commander.data.Protocol
import io.github.carnager.commander.data.Stores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Resolves a [Location.source] to its file system and keeps server connections alive between uses. */
class VfsRegistry(val local: LocalFs, private val stores: Stores, private val scope: CoroutineScope) {
    private val remotes = ConcurrentHashMap<String, Vfs>()

    /** Opened archives by source id, and where each archive file lives. */
    private val archives = ConcurrentHashMap<String, ArchiveFs>()
    private val archiveOrigins = ConcurrentHashMap<String, Location>()

    /** Last listing per directory, shown instantly while the fresh one loads. */
    val cache = ConcurrentHashMap<Location, List<FileEntry>>()

    operator fun get(source: String): Vfs {
        if (source == Location.LOCAL) return local
        if (isArchive(source)) return archives[source] ?: throw IOException(Res.str(R.string.err_archive_closed))
        return remotes.getOrPut(source) {
            val config = stores.servers.value.firstOrNull { it.id == source } ?: throw IOException(Res.str(R.string.err_server_gone))
            when (config.protocol) {
                Protocol.SFTP -> SftpFs(config, stores.secrets, stores.knownHosts)
                Protocol.SMB -> SmbFs(config, stores.secrets)
            }
        }
    }

    suspend fun home(source: String): String = when (val fs = get(source)) {
        is SftpFs -> fs.home()
        is SmbFs -> fs.home()
        else -> "/"
    }

    fun displayName(source: String): String = when {
        source == Location.LOCAL -> Res.str(R.string.source_device)
        isArchive(source) -> archives[source]?.displayName ?: Res.str(R.string.source_archive)
        else -> stores.servers.value.firstOrNull { it.id == source }?.name ?: "?"
    }

    fun isArchive(source: String) = source.startsWith(ARCHIVE_PREFIX)

    /** The archive file a source id was opened from, so "up" from its root leads back there. */
    fun archiveOrigin(source: String): Location? = archiveOrigins[source]

    /** Opens [file] (a local copy of the archive at [origin]) and returns the source id for tabs. */
    fun openArchive(origin: Location, file: File, scratchRoot: File): String {
        val id = ARCHIVE_PREFIX + Integer.toHexString(origin.hashCode())
        archives.getOrPut(id) { ArchiveFs(file, origin.name, File(scratchRoot, id.removePrefix(ARCHIVE_PREFIX)), id) }
        archiveOrigins[id] = origin
        cache.keys.removeAll { it.source == id }
        return id
    }

    /** Forgets an archive so the next open re-reads it, e.g. after it was overwritten. */
    fun closeArchive(origin: Location) {
        val id = ARCHIVE_PREFIX + Integer.toHexString(origin.hashCode())
        archives.remove(id)
        cache.keys.removeAll { it.source == id }
    }

    /** Drops a server's connection, e.g. after its settings changed. */
    fun invalidate(source: String) {
        val fs = remotes.remove(source)
        cache.keys.removeAll { it.source == source }
        scope.launch {
            when (fs) {
                is SftpFs -> fs.disconnect()
                is SmbFs -> fs.disconnect()
            }
        }
    }

    companion object {
        const val ARCHIVE_PREFIX = "arc:"
    }
}
