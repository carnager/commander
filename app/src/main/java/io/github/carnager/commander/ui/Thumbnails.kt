package io.github.carnager.commander.ui

import io.github.carnager.commander.R
import io.github.carnager.commander.Res
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import io.github.carnager.commander.vfs.Location
import io.github.carnager.commander.vfs.VfsRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** An image on a server or in an archive; [modified] and [size] make edits invalidate the thumbnail. */
data class RemoteThumb(val location: Location, val modified: Long, val size: Long)

/**
 * Fetches a remote image once, scales it down and keeps the result on disk, so a grid of server
 * photos costs one transfer per picture ever, not one per scroll.
 */
class RemoteThumbFetcher(
    private val data: RemoteThumb,
    private val vfs: VfsRegistry,
    private val dir: File,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val file = File(dir, key(data) + ".jpg")
        if (!file.exists()) withContext(Dispatchers.IO) {
            if (data.size > MAX_SOURCE) throw IOException(Res.str(R.string.err_thumb_too_large))
            val bytes = limit.withPermit { vfs[data.location.source].openRead(data.location.path).use { it.readBytes() } }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= EDGE && bounds.outHeight / (sample * 2) >= EDGE) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IOException(Res.str(R.string.err_not_an_image))
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()
            tmp.renameTo(file)
        }
        return SourceFetchResult(ImageSource(file.toOkioPath(), FileSystem.SYSTEM), "image/jpeg", DataSource.DISK)
    }

    class Factory(private val vfs: VfsRegistry, private val dir: File) : Fetcher.Factory<RemoteThumb> {
        override fun create(data: RemoteThumb, options: Options, imageLoader: ImageLoader) = RemoteThumbFetcher(data, vfs, dir)
    }

    class Key : Keyer<RemoteThumb> {
        override fun key(data: RemoteThumb, options: Options) = key(data)
    }

    companion object {
        private const val EDGE = 384
        private const val MAX_SOURCE = 40L * 1024 * 1024

        /** A few fetches at a time keep the listing itself responsive on slow links. */
        private val limit = Semaphore(3)

        fun key(d: RemoteThumb): String {
            val raw = "${d.location.source}|${d.location.path}|${d.modified}|${d.size}"
            return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

/** A full-size remote image for the viewer; cached on disk so swiping back and forth is free. */
data class RemoteImage(val location: Location, val modified: Long, val size: Long)

class RemoteImageFetcher(
    private val data: RemoteImage,
    private val vfs: VfsRegistry,
    private val dir: File,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val ext = data.location.name.substringAfterLast('.', "img").lowercase()
        val file = File(dir, key(data) + "." + ext)
        if (!file.exists()) withContext(Dispatchers.IO) {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            limit.withPermit {
                vfs[data.location.source].openRead(data.location.path).use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
            }
            tmp.renameTo(file)
        } else {
            // Touch it, so trimming the cache drops the least recently viewed images first.
            file.setLastModified(System.currentTimeMillis())
        }
        return SourceFetchResult(ImageSource(file.toOkioPath(), FileSystem.SYSTEM), mimeOf(data.location.name), DataSource.DISK)
    }

    class Factory(private val vfs: VfsRegistry, private val dir: File) : Fetcher.Factory<RemoteImage> {
        override fun create(data: RemoteImage, options: Options, imageLoader: ImageLoader) = RemoteImageFetcher(data, vfs, dir)
    }

    class Key : Keyer<RemoteImage> {
        override fun key(data: RemoteImage, options: Options) = "full:" + key(data)
    }

    companion object {
        private val limit = Semaphore(2)

        fun key(d: RemoteImage) = RemoteThumbFetcher.key(RemoteThumb(d.location, d.modified, d.size))

        /** Keeps the viewer cache below [maxBytes], oldest first. */
        fun trim(dir: File, maxBytes: Long) {
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            var total = 0L
            for (f in files) {
                total += f.length()
                if (total > maxBytes) f.delete()
            }
        }
    }
}
