package dev.easyide.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.easyide.app.data.settings.SafeModeReason
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.ThemeSettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.ui.AppViewModelFactory
import dev.easyide.app.ui.commands.Keymap
import dev.easyide.app.ui.foundation.LocalKeymap
import dev.easyide.app.ui.foundation.LocalSettings
import dev.easyide.app.ui.foundation.LocalMotionEnabled
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.foundation.currentWindowSize
import dev.easyide.app.ui.foundation.systemMotionEnabled
import dev.easyide.app.ui.navigation.AppNavHost
import dev.easyide.app.ui.theme.EasyIdeTheme
import dev.easyide.app.ui.theme.FileIcons
import dev.easyide.app.ui.theme.LocalFileIcons
import dev.easyide.app.ui.theme.LocalIconThemeChoices
import kotlinx.coroutines.launch

/**
 * Single-activity host. Provides the two ambient values every screen depends on
 * - the current window size class and whether motion is allowed - so no screen
 * has to look them up itself.
 */
class MainActivity : ComponentActivity() {

    // Main-thread only: written from composition, read by the splash's
    // per-frame keep-on-screen check.
    private var preferencesLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate, as core-splashscreen requires. The splash
        // covers the gap until DataStore answers, so the first frame the user
        // sees is already in the right theme and on the right start screen.
        installSplashScreen().setKeepOnScreenCondition { !preferencesLoaded }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as EasyIdeApplication).container
        val factory = AppViewModelFactory(container)
        // Only on a fresh launch: a recreate (e.g. "Exit safe mode") re-delivers
        // the same intent and must not turn safe mode straight back on.
        if (savedInstanceState == null && intent?.action == ACTION_SAFE_MODE) {
            container.safeMode.enterForSession(SafeModeReason.LAUNCHER_SHORTCUT)
        }

        setContent {
            // Null until DataStore's first read, like onboardingComplete, so
            // the splash can wait for the stored theme instead of drawing a
            // frame in the default one.
            val storedSettings by container.settingsStore.snapshot
                .collectAsStateWithLifecycle(initialValue = null)
            val onboardingComplete by container.uiPreferences.onboardingComplete
                .collectAsStateWithLifecycle(initialValue = null)
            val keymap by container.keymap.collectAsStateWithLifecycle(initialValue = null)
            val settings = storedSettings ?: SettingsSnapshot.DEFAULTS
            val themeMode = settings[SettingsSchema.themeMode]
            // Null (built-in palette) until a selected extension theme has loaded.
            val contributedTheme by container.extensions.colorTheme.collectAsStateWithLifecycle()
            SideEffect {
                preferencesLoaded = storedSettings != null && onboardingComplete != null
            }
            // First frame: onStartupFinished activations follow after the idle delay.
            LaunchedEffect(Unit) { container.extensions.onFirstFrame() }

            // Read once per composition rather than observed: the system
            // animation setting change restarts the activity anyway.
            val motionEnabled = remember { systemMotionEnabled() }
            val windowSize = currentWindowSize()

            val customizations = remember(settings) { ThemeSettingsSchema.customizations(settings) }
            val iconTheme by container.iconTheme.theme.collectAsStateWithLifecycle()
            val iconThemeChoices by container.iconTheme.choices.collectAsStateWithLifecycle()
            val fileIcons = remember(iconTheme) { iconTheme?.let(::FileIcons) }
            EasyIdeTheme(themeMode = themeMode, contributed = contributedTheme, customizations = customizations, themeLabel = settings[SettingsSchema.colorTheme].takeIf { it.isNotEmpty() }) {
                CompositionLocalProvider(
                    LocalWindowSize provides windowSize,
                    LocalMotionEnabled provides motionEnabled,
                    LocalSettings provides settings,
                    LocalKeymap provides (keymap?.keymap ?: Keymap.DEFAULT),
                    LocalFileIcons provides fileIcons,
                    LocalIconThemeChoices provides iconThemeChoices,
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        // Null means preferences have not loaded yet; showing
                        // nothing briefly beats flashing onboarding at a
                        // returning user.
                        onboardingComplete?.let { complete ->
                            AppNavHost(
                                startAtOnboarding = !complete,
                                motionEnabled = motionEnabled,
                                container = container,
                                viewModelFactory = factory,
                                onOnboardingComplete = {
                                    lifecycleScope.launch {
                                        container.uiPreferences.setOnboardingComplete(true)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** The launcher shortcut "Start in safe mode" (res/xml/shortcuts.xml). */
        const val ACTION_SAFE_MODE = "dev.easyide.app.action.SAFE_MODE"
    }
}
