package dev.easyide.app.ui.kit.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.theme.EasyIdeTheme
import dev.easyide.app.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden of the custom icon sheet at light and dark (identity.md 6): a changed pixel of any
 * glyph fails `:app:verifyRoborazziDebug`. Debug-only because the sheet is gallery code.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class IconSheetGoldenTest {

    @get:Rule val compose = createComposeRule()

    private fun sheet(mode: ThemeMode) {
        compose.setContent {
            EasyIdeTheme(themeMode = mode) { Sheet() }
        }
        compose.onRoot().captureRoboImage()
    }

    @Composable
    private fun Sheet() {
        Column(Modifier.fillMaxSize().background(Kit.colors.background)) { IconsSection() }
    }

    @Test fun iconSheetLight() = sheet(ThemeMode.LIGHT)

    @Test fun iconSheetDark() = sheet(ThemeMode.DARK)
}
