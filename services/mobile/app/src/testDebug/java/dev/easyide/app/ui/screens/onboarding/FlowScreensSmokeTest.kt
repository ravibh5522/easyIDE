package dev.easyide.app.ui.screens.onboarding

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.easyide.app.data.SandboxImages
import dev.easyide.app.ui.components.FlowFrame
import dev.easyide.app.ui.foundation.HeightClass
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.foundation.WindowSize
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.screens.diagnostics.CrashRecoveryDialog
import dev.easyide.app.ui.screens.newproject.EnvironmentChoice
import dev.easyide.app.ui.screens.newproject.NewProjectUiState
import dev.easyide.app.ui.screens.newproject.NewEnvironmentForm
import dev.easyide.app.ui.theme.EasyIdeTheme
import dev.easyide.app.ui.theme.ThemeMode
import dev.easyide.sandbox.bootstrap.InstallEvent
import dev.easyide.sandbox.model.SandboxBackend
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every state of the Install Linux content, the flow frame at both presentations and the dialogs compose and expose their action. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class FlowScreensSmokeTest {

    @get:Rule val compose = createComposeRule()

    private val images = SandboxImages.CATALOG

    private fun show(width: WidthClass = WidthClass.COMPACT, content: @Composable () -> Unit) = compose.setContent {
        EasyIdeTheme(themeMode = ThemeMode.DARK) {
            CompositionLocalProvider(LocalWindowSize provides WindowSize(width, HeightClass.REGULAR)) { content() }
        }
    }

    private fun content(stage: SetupStage) = EnvironmentSetupState(images, images.first().id, stage)

    private fun setup(state: EnvironmentSetupState) = show {
        EnvironmentSetupContent(state, onImageSelected = {}, onInstall = {}, onCancel = {}, onChooseAnother = {})
    }

    @Test fun `choosing lists every preset and offers install`() {
        setup(content(SetupStage.Choosing))
        images.forEach { compose.onNodeWithTag("kit:preset:${it.id}").assertIsDisplayed() }
        compose.onNodeWithText("Install").assertIsDisplayed()
    }

    @Test fun `installing shows the bar, the phase and cancel`() {
        val stage = SetupStage.Installing(images.first().label, InstallEvent.Downloading(50, 100, 0), 0.3f, null)
        setup(content(stage))
        compose.onNodeWithTag("kit:install-progress").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test fun `installing the packages shows the log tail`() {
        val stage = SetupStage.Installing(images.first().label, InstallEvent.Setup(1, 2, "apt"), 0.85f, "Setting up python3 (3.12)")
        setup(content(stage))
        compose.onNodeWithText("Setting up python3 (3.12)").assertIsDisplayed()
    }

    @Test fun `a failure offers retry and choosing another preset`() {
        val failure = ClassifiedFailure(InstallFailure.NETWORK, "timeout")
        setup(content(SetupStage.Failed(images.first().label, failure)))
        compose.onNodeWithText("Resume download").assertIsDisplayed()
        compose.onNodeWithText("Choose a different preset").assertIsDisplayed()
    }

    @Test fun `ready shows the installed tag`() {
        setup(content(SetupStage.Ready(images.first().label)))
        compose.onNodeWithTag("kit:install-ready").assertIsDisplayed()
    }

    private fun assertFrame(width: WidthClass) {
        show(width) {
            FlowFrame("New project", onBack = {}, footer = { KitButton("Create", {}) }) { BasicText("body") }
        }
        compose.onNodeWithText("New project").assertIsDisplayed()
        compose.onNodeWithText("body").assertIsDisplayed()
        compose.onNodeWithText("Create").assertIsDisplayed()
    }

    @Test fun `the flow frame is a page with a pinned action on a phone`() = assertFrame(WidthClass.COMPACT)

    @Test fun `the flow frame is a sheet with a pinned action on a wide window`() = assertFrame(WidthClass.EXPANDED)

    @Test fun `the new environment form marks a preset that is already installed`() {
        val state = NewProjectUiState(choice = EnvironmentChoice.CREATE_NEW, availableBackends = listOf(SandboxBackend.PROOT, SandboxBackend.CHROOT), selectedBackend = SandboxBackend.CHROOT)
        show { NewEnvironmentForm(state, {}, {}, {}) }
        compose.onNodeWithText("chroot runs as real root, outside Android's app sandbox. Faster, but a weaker boundary.").assertIsDisplayed()
    }

    @Test fun `the crash dialog keeps two actions and offers share in its content when it can reopen`() {
        show { CrashRecoveryDialog("java.lang.IllegalStateException: x", onReopenLastProject = {}, onShare = {}, onDismiss = {}) }
        compose.onNodeWithText("Reopen last project").assertIsDisplayed()
        compose.onNodeWithText("Share log").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
    }
}
