package dev.easyide.app.ui.screens.onboarding

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** What the device currently lets the app do, for the two permission steps. */
data class DeviceStatus(
    /** Exempt from battery optimisation, so Android does not pause the sandbox when the screen is off. */
    val batteryUnrestricted: Boolean,
    /** May post the foreground-service notification. Always true below Android 13. */
    val notificationsAllowed: Boolean,
)

/**
 * The status, re-read every time the app returns to the foreground: both
 * permissions are granted on system screens the user leaves the app for, so the
 * result is only knowable on return.
 */
@Composable
internal fun rememberDeviceStatus(): State<DeviceStatus> {
    val context = LocalContext.current
    val status = remember { mutableStateOf(DeviceAccess.status(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status.value = DeviceAccess.status(context) }
    return status
}

/** Reading and requesting the two capabilities; every intent here leaves the app, so each is a platform boundary. */
internal object DeviceAccess {

    fun status(context: Context) = DeviceStatus(
        batteryUnrestricted = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true,
        notificationsAllowed = Build.VERSION.SDK_INT < OnboardingSteps.NOTIFICATION_PERMISSION_MIN_SDK ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED,
    )

    /**
     * The one-tap system dialog for this app; if a vendor build lacks it, the
     * general battery-optimisation list, where the user can find the app.
     */
    fun requestBatteryExemption(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        if (!launch(context, direct)) launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** After the runtime prompt has been refused, the only way back is this screen. */
    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        launch(context, intent)
    }

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
