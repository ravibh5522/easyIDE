package dev.easyide.app.ui.screens.golden

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.screens.extensions.BrowseActions
import dev.easyide.app.ui.screens.extensions.BrowseUiState
import dev.easyide.app.ui.screens.extensions.ExtensionPageContent
import dev.easyide.app.ui.screens.extensions.ExtensionsPanelContent
import dev.easyide.app.ui.screens.extensions.PageActions
import dev.easyide.app.ui.screens.extensions.PageModel
import dev.easyide.app.ui.screens.extensions.PanelActions
import dev.easyide.app.ui.screens.extensions.TAB_CAPABILITIES
import dev.easyide.app.ui.screens.extensions.TAB_CONTRIBUTIONS
import dev.easyide.app.ui.screens.extensions.TAB_DETAILS
import dev.easyide.app.ui.screens.extensions.toItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of the Extensions panel and page at the three window configurations (density.md 4). The
 * panel is drawn at its token width on wide windows; on a phone it is the whole screen.
 * Record: `./gradlew :app:recordRoborazziDebug --tests '*ExtensionsGoldenTest'`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ExtensionsGoldenTest {

    @get:Rule val compose = createComposeRule()

    private val browse = BrowseUiState(updates = mapOf("acme.docker" to "2.4.0"))

    private val panelActions = PanelActions(
        onSelect = {}, onEnabled = { _, _ -> }, browse = BrowseActions({}, {}, {}), onRefresh = {}, onExitSafeMode = {},
        onClearLog = {}, onPickFile = {}, onPickFolder = {}, onCreate = {},
    )

    private val pageActions = PageActions({}, {}, {}, null, { _, _ -> }, { _, _ -> }, { _, _ -> }, {})

    private fun panel(wide: Boolean) = compose.goldenShot {
        Box(if (wide) Modifier.width(Kit.control.panelWidth).fillMaxHeight() else Modifier.fillMaxHeight()) {
            ExtensionsPanelContent(ExtensionFixtures.state, browse, selectedId = "acme.docker", actions = panelActions)
        }
    }

    private fun page(tab: Int) = compose.goldenShot {
        val row = ExtensionFixtures.docker
        ExtensionPageContent(PageModel(row, row.toItem("2.4.0", null), null, "2.4.0", emptyList()), tab, {}, pageActions)
    }

    @Test @Config(qualifiers = GoldenWindow.EXPANDED) fun extensionsPanelExpandedDense() = panel(wide = true)
    @Test @Config(qualifiers = GoldenWindow.MEDIUM) fun extensionsPanelMediumDense() = panel(wide = true)
    @Test @Config(qualifiers = GoldenWindow.COMPACT) fun extensionsPanelCompactComfortable() = panel(wide = false)

    @Test @Config(qualifiers = GoldenWindow.EXPANDED) fun extensionPageExpandedDense() = page(TAB_DETAILS)
    @Test @Config(qualifiers = GoldenWindow.MEDIUM) fun extensionPageMediumDense() = page(TAB_DETAILS)
    @Test @Config(qualifiers = GoldenWindow.COMPACT) fun extensionPageCompactComfortable() = page(TAB_DETAILS)

    @Test @Config(qualifiers = GoldenWindow.EXPANDED) fun extensionPageCapabilitiesExpandedDense() = page(TAB_CAPABILITIES)
    @Test @Config(qualifiers = GoldenWindow.COMPACT) fun extensionPageContributionsCompactComfortable() = page(TAB_CONTRIBUTIONS)
}
