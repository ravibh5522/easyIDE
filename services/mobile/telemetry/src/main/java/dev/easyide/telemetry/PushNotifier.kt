package dev.easyide.telemetry

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Shows a [PushMessage] as a notification. Provider-neutral, so a provider's service only maps its message type. */
class PushNotifier(private val context: Context) {

    /** [image] is the announcement's downloaded image, if it has one. */
    fun show(message: PushMessage, image: Bitmap? = null) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        ensureChannel()
        val open = message.link?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
        val tap = open?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val builder = NotificationCompat.Builder(context, TelemetryNames.PUSH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_announcement)
            .setContentTitle(message.title)
            .setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setAutoCancel(true)
            .setContentIntent(tap)
        image?.let { builder.setLargeIcon(iconSized(it)) }
        if (message.action != null && message.link != null) builder.addAction(0, message.action, tap)
        NotificationManagerCompat.from(context).notify(message.hashCode(), builder.build())
    }

    /** Downscaled to the large-icon slot: a full-size image would only cost memory and the binder transaction. */
    private fun iconSized(image: Bitmap): Bitmap {
        val side = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
        val scale = side.toFloat() / maxOf(image.width, image.height)
        if (scale >= 1f) return image
        return Bitmap.createScaledBitmap(image, (image.width * scale).toInt().coerceAtLeast(1), (image.height * scale).toInt().coerceAtLeast(1), true)
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
