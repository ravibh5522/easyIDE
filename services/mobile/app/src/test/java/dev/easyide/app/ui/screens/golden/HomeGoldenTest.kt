package dev.easyide.app.ui.screens.golden

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.screens.home.HomeNowPage
import dev.easyide.app.ui.screens.home.HomePanel
import dev.easyide.app.ui.screens.home.ProjectPage
import dev.easyide.sandbox.external.ExternalFolderSync
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of Home at the three window configurations (density.md 4). The panel is drawn at its
 * token width on wide windows, where it sits beside the stage; on a phone it is the whole screen
 * and carries the Now page. Record: `./gradlew :app:recordRoborazziDebug --tests '*HomeGoldenTest'`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeGoldenTest {

    @get:Rule val compose = createComposeRule()

    private val sync = ExternalFolderSync(RuntimeEnvironment.getApplication(), Dispatchers.Unconfined)

    private fun panel(showNow: Boolean) = compose.goldenShot {
        Box(if (showNow) Modifier.fillMaxHeight() else Modifier.width(Kit.control.panelWidth).fillMaxHeight()) {
            HomePanel(HomeFixtures.state, sync, HomeFixtures.callbacks, showNow = showNow, showHeader = false, selectedProjectId = "1")
        }
    }

    private fun nowPage() = compose.goldenShot { HomeNowPage(HomeFixtures.state, HomeFixtures.callbacks) }

    private fun projectPage() = compose.goldenShot { ProjectPage("1", HomeFixtures.state, HomeFixtures.callbacks) }

    @Test @Config(qualifiers = GoldenWindow.EXPANDED) fun homePanelExpandedDense() = panel(showNow = false)
    @Test @Config(qualifiers = GoldenWindow.MEDIUM) fun homePanelMediumDense() = panel(showNow = false)
    @Test @Config(qualifiers = GoldenWindow.COMPACT) fun homePanelCompactComfortable() = panel(showNow = true)

    @Test @Config(qualifiers = GoldenWindow.EXPANDED) fun homeNowPageExpandedDense() = nowPage()
    @Test @Config(qualifiers = GoldenWindow.MEDIUM) fun homeNowPageMediumDense() = nowPage()
    @Test @Config(qualifiers = GoldenWindow.COMPACT) fun homeNowPageCompactComfortable() = nowPage()

    @Test @Config(qualifiers = GoldenWindow.EXPANDED) fun projectPageExpandedDense() = projectPage()
    @Test @Config(qualifiers = GoldenWindow.MEDIUM) fun projectPageMediumDense() = projectPage()
    @Test @Config(qualifiers = GoldenWindow.COMPACT) fun projectPageCompactComfortable() = projectPage()
}
