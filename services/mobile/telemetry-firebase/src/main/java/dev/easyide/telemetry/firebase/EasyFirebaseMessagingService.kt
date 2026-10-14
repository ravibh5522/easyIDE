package dev.easyide.telemetry.firebase

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.easyide.telemetry.PushInbox
import dev.easyide.telemetry.PushMessage
import dev.easyide.telemetry.PushNotifier

/**
 * Notification messages land here only in the foreground; in the background the system draws them
 * itself on the channel declared in the manifest. Data messages always land here: they are
 * announcements (see [PushMessage.fromData]), shown as a notification and kept for the in-app dialog.
 */
class EasyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val note = message.notification
        if (note != null) {
            PushNotifier(this).show(PushMessage(title = note.title.orEmpty(), body = note.body.orEmpty()))
            return
        }
        val announcement = PushMessage.fromData(message.data) ?: return
        val inbox = PushInbox(this)
        inbox.put(announcement)
        PushNotifier(this).show(announcement, inbox.image())
    }
}
