package dev.easyide.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.easyide.app.ui.AppViewModelFactory
import dev.easyide.app.ui.foundation.LocalMotionEnabled
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.foundation.currentWindowSize
import dev.easyide.app.ui.foundation.systemMotionEnabled
import dev.easyide.app.ui.navigation.AppNavHost
import dev.easyide.app.ui.theme.LocalEditorColors
import dev.easyide.app.ui.theme.EasyIdeTheme
import dev.easyide.app.ui.theme.ThemeMode
import dev.easyide.app.ui.theme.editorColorsFor
import kotlinx.coroutines.launch

/**
 * Single-activity host. Provides the two ambient values every screen depends on
 * - the current window size class and whether motion is allowed - so no screen
 * has to look them up itself.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as EasyIdeApplication).container
        val factory = AppViewModelFactory(container)

        setContent {
            val themeMode by container.uiPreferences.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM_DEFAULT)
            val onboardingComplete by container.uiPreferences.onboardingComplete
                .collectAsStateWithLifecycle(initialValue = null)

            // Read once per composition rather than observed: the system
            // animation setting change restarts the activity anyway.
            val motionEnabled = remember { systemMotionEnabled() }
            val windowSize = currentWindowSize()
            val editorColors = editorColorsFor(themeMode, isSystemInDarkTheme())

            EasyIdeTheme(themeMode = themeMode) {
                CompositionLocalProvider(
                    LocalWindowSize provides windowSize,
                    LocalMotionEnabled provides motionEnabled,
                    LocalEditorColors provides editorColors,
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
}
