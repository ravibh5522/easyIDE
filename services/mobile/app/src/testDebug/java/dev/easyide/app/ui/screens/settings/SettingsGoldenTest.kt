package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.theme.EasyIdeTheme
import dev.easyide.app.ui.theme.ThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of the settings screens (density.md 4): the category panel and three pages, in the three
 * configurations the owner reads them in. Auto density follows the width class, so 1152dp and 720dp
 * are Dense and 411dp is Comfortable. Record with
 * `./gradlew :app:recordRoborazziDebug --tests '*SettingsGolden*'`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class SettingsGoldenBase {

    @get:Rule val compose = createComposeRule()

    /** Names the goldens: `settings/<config>_<screen>.png`. */
    protected abstract val config: String

    /** The system font size on top of the density: 2.0 is the largest the accessibility setting offers. */
    protected open val fontScale = 1f

    private fun shot(screen: String, content: @Composable () -> Unit) {
        setShot(content)
        compose.onNodeWithTag(SHOT).captureRoboImage("src/test/screenshots/settings/${config}_$screen.png")
    }

    private fun setShot(content: @Composable () -> Unit) = compose.setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
            EasyIdeTheme(themeMode = ThemeMode.DARK) {
                Box(Modifier.fillMaxWidth().background(Kit.colors.background).testTag(SHOT)) { content() }
            }
        }
    }

    /** Nothing of a page may reach past the window edge: chrome wraps or ellipsizes, it never overflows (U-DEN-05). */
    protected fun assertNoHorizontalOverflow(content: @Composable () -> Unit) {
        setShot { SettingsPageFrame { content() } }
        val root = compose.onRoot().fetchSemanticsNode().size.width
        // A segmented control scrolls sideways by design when its labels do not fit (U-DEN-05).
        val past = compose.onAllNodes(SemanticsMatcher("not a tab") { SemanticsProperties.Role !in it.config || it.config[SemanticsProperties.Role] != Role.Tab }).fetchSemanticsNodes()
            .filter { it.positionInRoot.x + it.size.width > root + 1 }
        assertTrue("nodes past the right edge: ${past.map { it.config }}", past.isEmpty())
    }

    private fun page(screen: String, content: @Composable () -> Unit) = shot(screen) { SettingsPageFrame { content() } }

    @Test fun panel() = shot("panel") {
        val wide = !Kit.metrics.width.isCompact
        Box(if (wide) Modifier.width(Kit.control.panelWidth) else Modifier.fillMaxWidth()) {
            SettingsPanelContent(SettingsFixtures.ui, null, "", "editor", {}, {}, {}, { _, _ -> })
        }
    }

    @Test fun panelSearching() = shot("panelSearching") {
        val wide = !Kit.metrics.width.isCompact
        Box(if (wide) Modifier.width(Kit.control.panelWidth) else Modifier.fillMaxWidth()) {
            SettingsPanelContent(SettingsFixtures.ui, null, "font", "search", {}, {}, {}, { _, _ -> })
        }
    }

    @Test fun appearancePage() = page("appearancePage") {
        PageToolbar("User", {}, false, "", {})
        AppearancePage(SettingsFixtures.env)
    }

    @Test fun editorPage() = page("editorPage") {
        PageToolbar("User", {}, true, "", {})
        CategoryRows(pageSettings(SettingsCategory.EDITOR, SettingsFixtures.env.settings), SettingsFixtures.env, "Editor")
    }

    @Test fun keyboardPage() = page("keyboardPage") { KeyboardContent(SettingsFixtures.keybindings, { _, _, _ -> }, {}, {}) }

    @Test fun searchResultsPage() = page("searchResultsPage") {
        SearchResultsPage(SettingsFixtures.env, SettingsFilter.parse("font"), SettingsFixtures.ui)
    }

    @Test fun editorPageStaysInsideTheWindow() = assertNoHorizontalOverflow {
        PageToolbar("User", {}, true, "", {})
        CategoryRows(pageSettings(SettingsCategory.EDITOR, SettingsFixtures.env.settings), SettingsFixtures.env, "Editor")
    }

    @Test fun keyboardPageStaysInsideTheWindow() = assertNoHorizontalOverflow {
        KeyboardContent(SettingsFixtures.keybindings, { _, _, _ -> }, {}, {})
    }

    @Test fun appearancePageStaysInsideTheWindow() = assertNoHorizontalOverflow {
        AppearancePage(SettingsFixtures.env)
    }

    private companion object { const val SHOT = "shot" }
}

@Config(sdk = [35], qualifiers = "w1152dp-h1800dp-xhdpi")
class SettingsGoldenExpandedTest : SettingsGoldenBase() { override val config = "expanded-dense-1152" }

@Config(sdk = [35], qualifiers = "w720dp-h1800dp-xhdpi")
class SettingsGoldenMediumTest : SettingsGoldenBase() { override val config = "medium-dense-720" }

@Config(sdk = [35], qualifiers = "w320dp-h2400dp-xhdpi")
class SettingsGoldenSmallLargeTextTest : SettingsGoldenBase() {
    override val config = "compact-320-font2"
    override val fontScale = LARGEST_FONT_SCALE
}

private const val LARGEST_FONT_SCALE = 2f

@Config(sdk = [35], qualifiers = "w411dp-h1800dp-xhdpi")
class SettingsGoldenCompactTest : SettingsGoldenBase() { override val config = "compact-comfortable-411" }
