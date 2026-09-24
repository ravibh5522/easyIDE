package dev.easyide.app.ui.screens.workspace.golden

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.props.Appearance
import dev.easyide.app.ui.props.Density as UiDensity
import dev.easyide.app.ui.theme.EasyIdeTheme
import dev.easyide.app.ui.theme.ThemeMode

/**
 * The window configurations the workspace goldens are recorded at: the widths and densities the owner's
 * tablet and a phone actually run at, plus a stress case (320dp wide, font scale 2) where chrome must still
 * be one line and inside its frame.
 */
enum class GoldenConfig(val id: String, val width: WidthClass, val density: UiDensity, val windowDp: Int, val fontScale: Float = 1f) {
    ExpandedDense("expanded-dense", WidthClass.EXPANDED, UiDensity.DENSE, 1152),
    MediumDense("medium-dense", WidthClass.MEDIUM, UiDensity.DENSE, 720),
    CompactComfortable("compact-comfortable", WidthClass.COMPACT, UiDensity.COMFORTABLE, 411),
    CompactStress("compact-320-font2", WidthClass.COMPACT, UiDensity.COMFORTABLE, 320, fontScale = 2f);

    /** A side panel's width there: the token on a wide window, the drawer's 360dp on a phone. */
    @Composable
    fun panel(): Dp = if (width.isCompact) minOf(windowDp.dp, PHONE_DRAWER) else Kit.control.panelWidth
}

private val PHONE_DRAWER = 360.dp

/**
 * Renders [content] in the theme of each [GoldenConfig] in turn and records one PNG per config,
 * `<name>-<config>.png`, under `src/test/screenshots/workspace`. [interact] runs once after the first render,
 * to put the UI in the state a shot needs (a row tapped).
 */
fun ComposeContentTestRule.record(name: String, interact: ComposeContentTestRule.() -> Unit = {}, content: @Composable (GoldenConfig) -> Unit) {
    var config by mutableStateOf(GoldenConfig.entries.first())
    setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, config.fontScale)) {
            EasyIdeTheme(themeMode = ThemeMode.DARK, appearance = Appearance(density = config.density), width = config.width) {
                Box(Modifier.background(Kit.colors.background)) { content(config) }
            }
        }
    }
    waitForIdle()
    interact()
    GoldenConfig.entries.forEach { c ->
        config = c
        waitForIdle()
        onRoot().captureRoboImage("src/test/screenshots/workspace/$name-${c.id}.png")
    }
}
