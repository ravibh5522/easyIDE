package dev.easyide.app.ui.screens.workspace.git

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether any of the app's activities is started (visible), from the platform's
 * own lifecycle callbacks - the same signal `ProcessLifecycleOwner` derives,
 * without adding a dependency for a counter. Auto-fetch consults it so the app
 * never spends data or battery on a fetch nobody can see the result of.
 *
 * Must be created while the process starts (before any activity), so the count
 * begins at zero; [dev.easyide.app.AppContainer] does.
 */
class AppForeground(application: Application) {

    private var started = 0
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    init {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                _isForeground.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                _isForeground.value = started > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
