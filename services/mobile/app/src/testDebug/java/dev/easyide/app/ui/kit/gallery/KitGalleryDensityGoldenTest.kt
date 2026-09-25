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
 * Goldens of the density gallery (density.md 4): the row anatomy and the type roles at Dense and
 * Comfortable side by side, on a tablet-landscape window (1152dp wide, where auto is Dense) in dark
 * and light. Record: `./gradlew :app:recordRoborazziDebug --tests '*KitGalleryDensityGoldenTest'`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1152dp-h800dp-xxhdpi")
class KitGalleryDensityGoldenTest {

    @get:Rule val compose = createComposeRule()

    private fun shot(mode: ThemeMode, content: @Composable (GalleryConfig) -> Unit) {
        val config = GalleryConfig(mode = mode)
        compose.setContent {
            EasyIdeTheme(themeMode = mode, appearance = config.appearance()) {
                Column(Modifier.fillMaxSize().background(Kit.colors.background)) { content(config) }
            }
        }
        compose.onRoot().captureRoboImage()
    }

    @Test fun rowAnatomyDenseAndComfortableDark() = shot(ThemeMode.DARK) { AnatomySection(it) }

    @Test fun rowAnatomyDenseAndComfortableLight() = shot(ThemeMode.LIGHT) { AnatomySection(it) }

    @Test fun typeRolesDenseAndComfortableDark() = shot(ThemeMode.DARK) { TypeSection(it) }
}
