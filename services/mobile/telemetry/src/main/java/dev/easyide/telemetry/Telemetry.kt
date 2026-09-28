package dev.easyide.telemetry

/** Event and property names in one place so a provider migration cannot silently rename a metric. */
object TelemetryNames {
    const val EVENT_APP_OPEN = "app_open"
    const val EVENT_WORKSPACE_OPEN = "workspace_open"

    const val PARAM_VERSION = "version_name"
    const val PARAM_BUILD = "version_code"

    const val PROP_CHANNEL = "build_channel"
    const val PROP_MAKER = "device_maker"
    const val PROP_MODEL = "device_model"
    const val PROP_SDK = "os_sdk"
    const val PROP_ABI = "cpu_abi"

    /** Broadcast topic every opted-in install joins. */
    const val TOPIC_ALL = "all"
    const val TOPIC_CHANNEL_PREFIX = "channel_"

    const val PUSH_CHANNEL_ID = "announcements"
}

/** Usage analytics. Off until [setEnabled] is called with true; a provider must not collect before that. */
interface Analytics {
    fun setEnabled(enabled: Boolean)
    fun log(event: String, params: Map<String, String> = emptyMap())
    fun setUserProperty(name: String, value: String)
}

/** Push messaging. No registration with the provider happens until [setEnabled] is called with true. */
interface Push {
    fun setEnabled(enabled: Boolean)
    fun subscribe(topic: String)
    fun unsubscribe(topic: String)
}

/** Crash and error reporting. Uncaught crashes are captured by the provider itself once enabled. */
interface CrashReporter {
    fun setEnabled(enabled: Boolean)
    fun setKey(name: String, value: String)
    fun log(message: String)
    fun recordError(error: Throwable)
}

/** Performance monitoring: the provider's automatic traces (startup, screen rendering) plus named traces. */
interface Performance {
    fun setEnabled(enabled: Boolean)
    fun startTrace(name: String): Trace
}

fun interface Trace {
    fun stop()
}

/** Remotely tunable values. Reads return the caller's default until a fetch has succeeded after consent. */
interface RemoteConfig {
    fun refresh()
    fun bool(key: String, default: Boolean): Boolean
    fun string(key: String, default: String): String
}

/** What [PushNotifier] shows. Provider services map their message type onto this. */
data class PushMessage(val title: String, val body: String)

/** The provider handed to the app: one object so swapping providers is one construction site. */
class Telemetry(
    val analytics: Analytics,
    val push: Push,
    val crash: CrashReporter = NoCrash,
    val performance: Performance = NoPerformance,
    val remoteConfig: RemoteConfig = NoRemoteConfig,
) {
    companion object {
        /** Used when no provider is configured (a build without its config file): every call is a no-op. */
        val NONE = Telemetry(
            analytics = object : Analytics {
                override fun setEnabled(enabled: Boolean) = Unit
                override fun log(event: String, params: Map<String, String>) = Unit
                override fun setUserProperty(name: String, value: String) = Unit
            },
            push = object : Push {
                override fun setEnabled(enabled: Boolean) = Unit
                override fun subscribe(topic: String) = Unit
                override fun unsubscribe(topic: String) = Unit
            },
        )
    }
}

private object NoCrash : CrashReporter {
    override fun setEnabled(enabled: Boolean) = Unit
    override fun setKey(name: String, value: String) = Unit
    override fun log(message: String) = Unit
    override fun recordError(error: Throwable) = Unit
}

private object NoPerformance : Performance {
    override fun setEnabled(enabled: Boolean) = Unit
    override fun startTrace(name: String) = Trace { }
}

private object NoRemoteConfig : RemoteConfig {
    override fun refresh() = Unit
    override fun bool(key: String, default: Boolean) = default
    override fun string(key: String, default: String) = default
}
