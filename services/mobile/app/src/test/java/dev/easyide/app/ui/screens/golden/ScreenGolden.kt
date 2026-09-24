package dev.easyide.app.ui.screens.golden

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.foundation.currentWindowSize
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.theme.EasyIdeTheme
import dev.easyide.app.ui.theme.ThemeMode

/**
 * The three window configurations the density goldens record (density.md 4). The width class picks
 * the density, so Robolectric qualifiers are all a test sets: EXPANDED and MEDIUM render Dense, COMPACT
 * renders Comfortable. Heights are tall enough that a scrolling screen shows all of its content.
 */
internal object GoldenWindow {
    const val EXPANDED = "w1152dp-h1400dp-xhdpi"
    const val MEDIUM = "w720dp-h1400dp-xhdpi"
    const val COMPACT = "w411dp-h1400dp-xhdpi"
}

/** Renders [content] on the app background in [EasyIdeTheme] and records the root as this test's golden. */
internal fun ComposeContentTestRule.goldenShot(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    setContent {
        EasyIdeTheme(themeMode = mode) {
            CompositionLocalProvider(LocalWindowSize provides currentWindowSize()) {
                Box(Modifier.fillMaxSize().background(Kit.colors.background)) { content() }
            }
        }
    }
    onRoot().captureRoboImage()
}
