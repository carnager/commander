package io.github.carnager.commander.media

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import io.github.carnager.commander.vfs.FileEntry
import io.github.carnager.commander.vfs.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the mini player and the player sheet show. */
data class NowPlaying(
    val title: String,
    val artist: String?,
    val album: String?,
    val playing: Boolean,
    val buffering: Boolean,
    val index: Int,
    /** File names of the queue, in order. */
    val queue: List<String>,
    val duration: Long,
    val artwork: ByteArray?,
    val repeat: Int,
    val shuffle: Boolean,
    /** The folder the queue was started from. */
    val dir: Location?,
)

/**
 * The app's side of [PlaybackService]: queues a folder's audio files and mirrors the player's
 * state for the UI. Connects lazily, so the service only starts once something is played.
 */
class AudioController(private val context: Context, private val onError: (String) -> Unit) {
    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state
    private var controller: MediaController? = null
    private var pending: ((MediaController) -> Unit)? = null
    private var connecting = false
    private var dir: Location? = null

    val position: Long get() = controller?.currentPosition ?: 0

    // Connect right away: after the app was closed, music may still be playing in the service.
    init {
        withController {}
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()

        override fun onPlayerError(error: PlaybackException) {
            onError(error.cause?.message ?: error.message ?: error.errorCodeName)
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let { return block(it) }
        pending = block
        if (connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrElse { e -> onError(e.message ?: e.javaClass.simpleName); return@addListener }
            controller = c
            c.addListener(listener)
            pending?.invoke(c)
            pending = null
            publish()
        }, MoreExecutors.directExecutor())
    }

    /** Plays [files] of [folder] as the queue, starting at [start]. */
    fun play(folder: Location, files: List<FileEntry>, start: Int) {
        dir = folder
        val items = files.map { e ->
            val uri = VfsDataSource.uriFor(folder.child(e.name), e.size)
            MediaItem.Builder()
                .setMediaId(folder.child(e.name).let { "${it.source}:${it.path}" })
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(MediaMetadata.Builder().setDisplayTitle(e.name.substringBeforeLast('.')).build())
                .build()
        }
        withController { c ->
            c.setMediaItems(items, start, 0)
            c.prepare()
            c.play()
        }
    }

    fun toggle() = controller?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPrevious()
    fun seek(ms: Long) = controller?.seekTo(ms)
    fun jump(index: Int) = controller?.seekTo(index, 0)
    fun cycleRepeat() = controller?.let { it.repeatMode = (it.repeatMode + 1) % 3 }
    fun toggleShuffle() = controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun stop() {
        controller?.run {
            stop()
            clearMediaItems()
        }
    }

    fun release() {
        controller?.release()
        controller = null
    }

    private fun publish() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            _state.value = null
            return
        }
        val m = c.mediaMetadata
        val item = c.currentMediaItem
        _state.value = NowPlaying(
            title = m.title?.toString()?.takeIf { it.isNotBlank() } ?: item?.mediaMetadata?.displayTitle?.toString().orEmpty(),
            artist = (m.artist ?: m.albumArtist)?.toString(),
            album = m.albumTitle?.toString(),
            playing = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            index = c.currentMediaItemIndex,
            queue = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaMetadata.displayTitle?.toString().orEmpty() },
            duration = c.duration.coerceAtLeast(0),
            artwork = m.artworkData,
            repeat = c.repeatMode,
            shuffle = c.shuffleModeEnabled,
            dir = dir,
        )
    }
}
