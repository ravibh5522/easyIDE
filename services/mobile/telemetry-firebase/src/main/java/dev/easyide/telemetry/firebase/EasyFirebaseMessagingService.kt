package dev.easyide.telemetry.firebase

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.easyide.telemetry.PushMessage
import dev.easyide.telemetry.PushNotifier

/**
 * Only foreground delivery lands here for notification messages; in the background the system
 * draws them itself on the channel declared in the manifest. Data-only messages are not shown:
 * the app has no data handling for them yet.
 */
class EasyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val note = message.notification ?: return
        PushNotifier(this).show(PushMessage(title = note.title.orEmpty(), body = note.body.orEmpty()))
    }
}
