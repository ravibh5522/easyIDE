package dev.easyide.telemetry.firebase

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.messaging.FirebaseMessaging
import dev.easyide.telemetry.Analytics
import dev.easyide.telemetry.Push
import dev.easyide.telemetry.PushNotifier
import dev.easyide.telemetry.Telemetry

object FirebaseTelemetry {

    /**
     * Firebase reads its config from `google-services.json`, which the Gradle plugin turns into
     * resources. A build without that file (a fork, a local checkout) has no [FirebaseApp], so it
     * gets [Telemetry.NONE] instead of a crash.
     */
    fun create(context: Context): Telemetry {
        if (FirebaseApp.getApps(context).isEmpty()) return Telemetry.NONE
        return Telemetry(FirebaseAnalyticsAdapter(context), FirebaseMessagingAdapter(context))
    }
}

private class FirebaseAnalyticsAdapter(context: Context) : Analytics {
    private val firebase = FirebaseAnalytics.getInstance(context)

    override fun setEnabled(enabled: Boolean) = firebase.setAnalyticsCollectionEnabled(enabled)

    override fun log(event: String, params: Map<String, String>) {
        val bundle = Bundle()
        params.forEach { (k, v) -> bundle.putString(k, v) }
        firebase.logEvent(event, bundle)
    }

    override fun setUserProperty(name: String, value: String) = firebase.setUserProperty(name, value)
}

private class FirebaseMessagingAdapter(private val context: Context) : Push {
    private val messaging = FirebaseMessaging.getInstance()

    override fun setEnabled(enabled: Boolean) {
        // The channel must exist before a message the system draws itself arrives.
        if (enabled) PushNotifier(context).ensureChannel()
        messaging.isAutoInitEnabled = enabled
        // Turning off also deletes the registration, so the server can no longer reach this install.
        if (!enabled) messaging.deleteToken()
    }

    override fun subscribe(topic: String) {
        messaging.subscribeToTopic(topic)
    }

    override fun unsubscribe(topic: String) {
        messaging.unsubscribeFromTopic(topic)
    }
}
