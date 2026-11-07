package io.github.carnager.commander.ops

import io.github.carnager.commander.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.text.format.Formatter
import io.github.carnager.commander.CommanderApp
import io.github.carnager.commander.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while [Transfers] works, so a long copy survives the user
 * leaving the app. The work itself stays in [Transfers]; this only mirrors its progress.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val transfers = (application as CommanderApp).transfers
        if (intent?.action == ACTION_CANCEL) {
            transfers.cancel()
            return START_NOT_STICKY
        }
        startForeground(ID, build(transfers.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        loop?.cancel()
        loop = scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            while (true) {
                val state = transfers.state.value
                if (state == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }
                nm.notify(ID, build(state))
                // Notification updates are rate limited by the system; twice a second is plenty.
                delay(500)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 caps dataSync services; give up cleanly rather than being killed.
        (application as CommanderApp).transfers.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(state: TransferState?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(state?.title ?: getString(R.string.notif_transfer_title))
            .setContentText(state?.current ?: "")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_cancel), cancel).build())
        if (state != null) {
            val p = state.progress
            if (p == null) b.setProgress(0, 0, true) else b.setProgress(1000, (p * 1000).toInt(), false)
            if (state.bytesTotal > 0) {
                b.setSubText(
                    Formatter.formatShortFileSize(this, state.bytesDone) + " / " + Formatter.formatShortFileSize(this, state.bytesTotal)
                )
            }
        }
        return b.build()
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "transfers"
        private const val ACTION_CANCEL = "cancel"

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notif_channel_transfers), NotificationManager.IMPORTANCE_LOW)
            )
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TransferService::class.java))
        }
    }
}
