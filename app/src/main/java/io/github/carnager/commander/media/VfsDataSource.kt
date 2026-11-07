@file:OptIn(UnstableApi::class)

package io.github.carnager.commander.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.github.carnager.commander.vfs.Location
import io.github.carnager.commander.vfs.VfsRegistry
import kotlinx.coroutines.runBlocking
import java.io.InputStream

/**
 * Lets the player read any file Commander can reach, device or server alike: a seek reopens the
 * file at the new offset, which every [io.github.carnager.commander.vfs.Vfs] supports.
 */
class VfsDataSource(private val vfs: VfsRegistry) : BaseDataSource(true) {
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val loc = locationOf(dataSpec.uri)
        val size = dataSpec.uri.getQueryParameter(SIZE)?.toLongOrNull() ?: -1
        input = runBlocking { vfs[loc.source].openRead(loc.path, dataSpec.position) }
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            size >= 0 -> (size - dataSpec.position).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val want = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(remaining, length.toLong()).toInt()
        val n = input!!.read(buffer, offset, want)
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            input?.close()
        } finally {
            input = null
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val vfs: VfsRegistry) : DataSource.Factory {
        override fun createDataSource(): DataSource = VfsDataSource(vfs)
    }

    companion object {
        private const val SCHEME = "commander"
        private const val SIZE = "n"

        /** The player's address for a file; the size spares a stat call per seek. */
        fun uriFor(loc: Location, size: Long): Uri = Uri.Builder()
            .scheme(SCHEME).authority("vfs")
            .appendQueryParameter("s", loc.source)
            .appendQueryParameter("p", loc.path)
            .appendQueryParameter(SIZE, size.toString())
            .build()

        fun locationOf(uri: Uri) = Location(uri.getQueryParameter("s")!!, uri.getQueryParameter("p")!!)
    }
}
