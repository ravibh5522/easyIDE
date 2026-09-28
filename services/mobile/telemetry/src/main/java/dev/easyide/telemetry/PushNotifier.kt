package dev.easyide.telemetry

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Shows a [PushMessage] as a notification. Provider-neutral, so a provider's service only maps its message type. */
class PushNotifier(private val context: Context) {

    fun show(message: PushMessage) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        ensureChannel()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val tap = launch?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val notification = NotificationCompat.Builder(context, TelemetryNames.PUSH_CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(message.title)
            .setContentText(message.body)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        NotificationManagerCompat.from(context).notify(message.hashCode(), notification)
    }

    /** Also called by providers that let the system draw the notification, so its channel exists first. */
    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(TelemetryNames.PUSH_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            TelemetryNames.PUSH_CHANNEL_ID,
            context.getString(R.string.push_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = context.getString(R.string.push_channel_desc)
        manager.createNotificationChannel(channel)
    }
}
