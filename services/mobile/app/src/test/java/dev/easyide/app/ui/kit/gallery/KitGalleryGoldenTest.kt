package dev.easyide.app.ui.kit.gallery

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.theme.EasyIdeFonts
import dev.easyide.app.ui.theme.EasyIdeTheme
import dev.easyide.app.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshot spike for the kit gallery (docs/ui-redesign/kit.md 7): one placeholder
 * composable in [EasyIdeTheme] proves the render pipeline and the bundled Geist fonts.
 *
 * Record goldens: `./gradlew :app:recordRoborazziDebug`
 * Verify against them: `./gradlew :app:verifyRoborazziDebug`
 * Goldens live in `app/src/test/screenshots` and are checked in; a plain
 * `:app:testDebugUnitTest` renders but does not compare.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// SDK 35, not compileSdk 37: Espresso (pulled in by the compose test rule) calls
// InputManager.getInstance(), which Robolectric's SDK 37 android-all no longer has.
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class KitGalleryGoldenTest {

    @get:Rule val compose = createComposeRule()

    @Test fun placeholderInGeistUnderEasyIdeTheme() {
        compose.setContent {
            EasyIdeTheme(themeMode = ThemeMode.DARK) {
                Column(Modifier.fillMaxWidth().background(Kit.colors.background).padding(Kit.space.m)) {
                    Text("Settings", style = Kit.text.display, color = Kit.colors.plainText)
                    Text("Sans 0123456789 Ag", fontFamily = EasyIdeFonts.sans, color = Kit.colors.plainText)
                    Text("Mono 0123456789 Ag", fontFamily = EasyIdeFonts.mono, color = Kit.colors.plainText)
                }
            }
        }
        compose.onRoot().captureRoboImage()
    }

    @Test fun bundledGeistFontsLoad() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(R.font.geist_regular, R.font.geist_medium, R.font.geist_semibold, R.font.geist_mono_regular, R.font.geist_mono_bold)
            .forEach { assertNotNull(ResourcesCompat.getFont(context, it)) }
    }
}
