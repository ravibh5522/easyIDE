package dev.easyide.sandbox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeps the sandbox process tree alive while the app is backgrounded. Android
 * reaps background app processes within seconds, which would kill a running dev
 * server or an in-flight Claude Code session - see
 * docs/sandbox-runtime/arch.md SS4.
 *
 * This is the lifecycle host only; it does not yet spawn the Theia backend,
 * tmux server or agents (tracked in docs/sandbox-runtime/tracker.md).
 */
class SandboxForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        // START_STICKY so the OS re-creates the service after a low-memory
        // kill; the sandbox state itself is rebuilt from disk on next start.
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(NOTIFICATION_TEXT)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL_ID = "sandbox_runtime"
        private const val CHANNEL_NAME = "Sandbox runtime"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_TITLE = "Sandbox running"
        private const val NOTIFICATION_TEXT = "Keeping terminals and servers alive"

        fun start(context: Context) {
            val intent = Intent(context, SandboxForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SandboxForegroundService::class.java))
        }
    }
}
