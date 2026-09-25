package dev.easyide.app.ui.kit.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.easyide.app.extensions.adapters.IconThemeFile
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.screens.workspace.syntax.TextMateHighlighter
import dev.easyide.app.ui.theme.EasyIdeTheme
import dev.easyide.app.ui.theme.FileIcons
import dev.easyide.app.ui.theme.LocalFileIcons
import dev.easyide.app.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Golden of a sample project tree drawn by the vendored Material Icon Theme at 16dp and 24dp, dark and
 * light, through the real SVG rasteriser at xxhdpi. Python must be the snake, Docker the whale.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w900dp-h900dp-xxhdpi")
class MaterialIconTreeGoldenTest {

    private companion object {
        const val SETTLE_ROUNDS = 6
        const val SETTLE_MS = 500L
    }

    @get:Rule val compose = createComposeRule()

    private fun tree(mode: ThemeMode) {
        TextMateHighlighter.init(ApplicationProvider.getApplicationContext())
        val root = File("src/main/assets/extensions/easyide.material-icons")
        val themeFile = File(root, "dist/material-icons.json")
        val theme = IconThemeFile.parse("material-icon-theme", themeFile.readText(), themeFile, root)!!
        compose.setContent {
            EasyIdeTheme(themeMode = mode) {
                CompositionLocalProvider(LocalFileIcons provides FileIcons(theme)) {
                    Column(Modifier.fillMaxSize().background(Kit.colors.background)) { IconTreeSection() }
                }
            }
        }
        repeat(SETTLE_ROUNDS) {
            Thread.sleep(SETTLE_MS)
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage()
    }

    @Test fun materialIconTreeDark() = tree(ThemeMode.DARK)

    @Test fun materialIconTreeLight() = tree(ThemeMode.LIGHT)
}
