package dev.easyide.sandbox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.drawable.Icon
import android.os.IBinder
import dev.easyide.sandbox.R

/**
 * Keeps the app process at foreground priority while workspaces are running, so terminals
 * and dev servers survive the app being backgrounded for as long as Android allows - see
 * docs/sandbox-runtime/arch.md SS4. It owns no processes itself: they are children of the
 * app process and this service is what keeps that process alive. Its lifetime follows
 * [SandboxKeepAlive]: started with the first live session, stopped with the last.
 *
 * Does not spawn tmux or a Theia backend, and does not make sessions survive the process
 * being killed; see [SandboxKeepAlive].
 */
class SandboxForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Within seconds of startForegroundService, whatever the intent is.
        startForeground(NOTIFICATION_ID, buildNotification(SandboxKeepAlive.sessions))
        if (intent?.action == ACTION_STOP_ALL) SandboxKeepAlive.host?.endAllSessions()
        // Nothing live in this process (the system re-created us, or the last session just ended).
        if (SandboxKeepAlive.sessions <= 0) stopSelf()
        // Not sticky: a restarted service would find no sessions, because they died with the process.
        return START_NOT_STICKY
    }

    /**
     * Android 15 caps a `dataSync` service at six hours a day and then calls this; the
     * service must stop within seconds or the app is crashed. Sessions keep running
     * unprotected until the next one starts the service again.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    private fun buildNotification(sessions: Int): Notification {
        ensureChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val stopAll = PendingIntent.getService(
            this,
            0,
            Intent(this, SandboxForegroundService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return builder
            .setContentTitle(getString(R.string.sandbox_fgs_title))
            .setContentText(resources.getQuantityString(R.plurals.sandbox_fgs_text, sessions, sessions))
            .setSmallIcon(R.drawable.ic_stat_easyide)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, getString(R.string.sandbox_fgs_stop), stopAll).build())
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.sandbox_fgs_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL_ID = "sandbox_runtime"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP_ALL = "dev.easyide.sandbox.action.STOP_ALL"

        fun start(context: Context) {
            val intent = Intent(context, SandboxForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SandboxForegroundService::class.java))
        }
    }
}
