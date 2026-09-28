package dev.easyide.telemetry.firebase

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import dev.easyide.telemetry.Analytics
import dev.easyide.telemetry.CrashReporter
import dev.easyide.telemetry.Performance
import dev.easyide.telemetry.RemoteConfig
import dev.easyide.telemetry.Trace
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
        return Telemetry(
            analytics = FirebaseAnalyticsAdapter(context),
            push = FirebaseMessagingAdapter(context),
            crash = FirebaseCrashAdapter(),
            performance = FirebasePerformanceAdapter(),
            remoteConfig = FirebaseRemoteConfigAdapter(),
        )
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

private class FirebaseCrashAdapter : CrashReporter {
    private val crashlytics = FirebaseCrashlytics.getInstance()

    override fun setEnabled(enabled: Boolean) = crashlytics.setCrashlyticsCollectionEnabled(enabled)
    override fun setKey(name: String, value: String) = crashlytics.setCustomKey(name, value)
    override fun log(message: String) = crashlytics.log(message)
    override fun recordError(error: Throwable) = crashlytics.recordException(error)
}

private class FirebasePerformanceAdapter : Performance {
    private val perf = FirebasePerformance.getInstance()

    override fun setEnabled(enabled: Boolean) = perf.setPerformanceCollectionEnabled(enabled)

    override fun startTrace(name: String): Trace {
        val trace = perf.newTrace(name)
        trace.start()
        return Trace { trace.stop() }
    }
}

private class FirebaseRemoteConfigAdapter : RemoteConfig {
    private val config = FirebaseRemoteConfig.getInstance()

    override fun refresh() {
        config.fetchAndActivate()
    }

    override fun bool(key: String, default: Boolean): Boolean =
        if (config.getValue(key).source == FirebaseRemoteConfig.VALUE_SOURCE_REMOTE) config.getBoolean(key) else default

    override fun string(key: String, default: String): String =
        if (config.getValue(key).source == FirebaseRemoteConfig.VALUE_SOURCE_REMOTE) config.getString(key) else default
}
