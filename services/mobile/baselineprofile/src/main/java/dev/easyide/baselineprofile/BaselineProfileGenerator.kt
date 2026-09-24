package dev.easyide.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the classes and methods touched on the two paths users feel most:
 * cold start to Home, and opening a workspace from Home.
 *
 * Opening a workspace needs an existing project, and creating one downloads a
 * rootfs, so the generator does not create it: run it on a device that
 * already has at least one project. Without one, only startup is recorded.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndOpenWorkspace() = rule.collect(packageName = targetAppId()) {
        pressHome()
        startActivityAndWait()

        // First run lands on onboarding. Text mirrors R.string.onboarding_continue
        // in :app, which this module cannot reference.
        device.wait(Until.findObject(By.text(ONBOARDING_CONTINUE)), UI_TIMEOUT_MS)?.click()

        // Home's project grid is the only scrollable node; its first child is
        // the first project card.
        val grid = device.wait(Until.findObject(By.scrollable(true)), UI_TIMEOUT_MS)
        grid?.children?.firstOrNull()?.click() ?: return@collect
        device.waitForIdle()
    }

    // Passed by the baselineprofile plugin, so the same generator drives both
    // release (dev.easyide.app) and canary (dev.easyide.app.canary).
    private fun targetAppId(): String =
        checkNotNull(InstrumentationRegistry.getArguments().getString("targetAppId")) {
            "targetAppId instrumentation argument missing; run via :app:generateBaselineProfile"
        }

    private companion object {
        const val ONBOARDING_CONTINUE = "Get started"
        const val UI_TIMEOUT_MS = 5_000L
    }
}
