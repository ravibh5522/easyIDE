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
 * Golden of the file icon sheet at 16dp and 24dp in dark and light, drawn by the real pack through the
 * real SVG rasteriser at xxhdpi. A changed pixel of any icon fails `:app:verifyRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w900dp-h1200dp-xxhdpi")
class FileIconSheetGoldenTest {

    private companion object {
        const val SETTLE_ROUNDS = 8
        const val SETTLE_MS = 500L
    }

    @get:Rule val compose = createComposeRule()

    private fun sheet(mode: ThemeMode) {
        TextMateHighlighter.init(ApplicationProvider.getApplicationContext())
        val root = File("src/main/assets/extensions/easyide.file-icons")
        val themeFile = File(root, "icons/easyide-file-icons.json")
        val theme = IconThemeFile.parse("easyide-file-icons", themeFile.readText(), themeFile, root)!!
        compose.setContent {
            EasyIdeTheme(themeMode = mode) {
                CompositionLocalProvider(LocalFileIcons provides FileIcons(theme)) {
                    Column(Modifier.fillMaxSize().background(Kit.colors.background)) { FileIconsSection() }
                }
            }
        }
        // Icons are read and rasterised on IO; let them land and recompose before the capture.
        repeat(SETTLE_ROUNDS) {
            Thread.sleep(SETTLE_MS)
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage()
    }

    @Test fun fileIconSheetDark() = sheet(ThemeMode.DARK)

    @Test fun fileIconSheetLight() = sheet(ThemeMode.LIGHT)
}
