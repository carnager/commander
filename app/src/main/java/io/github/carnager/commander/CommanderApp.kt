package io.github.carnager.commander

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import io.github.carnager.commander.ui.RemoteImageFetcher
import io.github.carnager.commander.ui.RemoteThumbFetcher
import io.github.carnager.commander.data.Stores
import io.github.carnager.commander.ops.TransferService
import io.github.carnager.commander.ops.Transfers
import io.github.carnager.commander.ops.Trash
import io.github.carnager.commander.vfs.Location
import kotlinx.coroutines.launch
import io.github.carnager.commander.vfs.LocalFs
import io.github.carnager.commander.vfs.VfsRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class CommanderApp : Application(), SingletonImageLoader.Factory {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var stores: Stores
    lateinit var vfs: VfsRegistry
    lateinit var transfers: Transfers

    override fun onCreate() {
        super.onCreate()
        Res.app = this
        // Android ships a stripped-down "BC" provider; sshj needs the full one for Ed25519 and modern ciphers.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        stores = Stores(this)
        vfs = VfsRegistry(LocalFs(this), stores, scope)
        TransferService.createChannel(this)
        // Archive scratch space from the last session is never reused.
        java.io.File(cacheDir, "archives").deleteRecursively()
        val trash = Trash(vfs)
        transfers = Transfers(
            vfs, scope, java.io.File(cacheDir, "pack"), trash,
            onStart = { TransferService.start(this) },
            onLocalChange = ::rescan,
        )
        // The device trash keeps things for 30 days, like Android's own.
        scope.launch(Dispatchers.IO) {
            RemoteImageFetcher.trim(java.io.File(cacheDir, "images"), 300L * 1024 * 1024)
            for ((_, volume) in vfs.local.volumes()) {
                runCatching { trash.purgeOlderThan(Location(Location.LOCAL, "$volume/${Trash.LOCAL_DIR}"), 30) }
            }
        }
    }

    /**
     * Tells Android's media index about paths we changed with plain file calls; otherwise galleries
     * keep showing deleted photos and miss new ones until the next full scan.
     */
    fun rescan(paths: List<String>) {
        android.media.MediaScannerConnection.scanFile(this, paths.distinct().toTypedArray(), null, null)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
                add(RemoteThumbFetcher.Factory(vfs, java.io.File(cacheDir, "thumbs")))
                add(RemoteThumbFetcher.Key())
                add(RemoteImageFetcher.Factory(vfs, java.io.File(cacheDir, "images")))
                add(RemoteImageFetcher.Key())
            }
            .build()
}
