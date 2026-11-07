@file:OptIn(UnstableApi::class)

package io.github.carnager.commander.media

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.carnager.commander.CommanderApp
import io.github.carnager.commander.ui.MainActivity

/**
 * Plays audio in the background, with the media notification and lock screen controls that come
 * with a media session. The app's screens talk to it through a MediaController.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val vfs = (application as CommanderApp).vfs
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(VfsDataSource.Factory(vfs)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, TitledPlayer(player))
            .setSessionActivity(open)
            .setCallback(Callback(packageName))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    /** Swiped away from recents: keep playing if music is on, otherwise go. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Untagged files have no title: show the file name (the item's display title) instead of nothing. */
    private class TitledPlayer(player: Player) : ForwardingPlayer(player) {
        override fun getMediaMetadata(): MediaMetadata {
            val m = super.getMediaMetadata()
            return if (m.title.isNullOrBlank() && m.displayTitle != null) m.buildUpon().setTitle(m.displayTitle).build() else m
        }
    }

    /**
     * Items arrive from controllers with their address in the request metadata; give them their URI
     * back. Only Commander itself queues files: the service is exported for system controls
     * (notification, Bluetooth, lock screen), which must not get to read any file it can reach.
     */
    private class Callback(private val own: String) : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = if (controller.packageName != own) {
            Futures.immediateFailedFuture(UnsupportedOperationException())
        } else Futures.immediateFuture(
            mediaItems.map { item ->
                item.requestMetadata.mediaUri?.let { item.buildUpon().setUri(it).build() } ?: item
            }.toMutableList()
        )
    }
}
