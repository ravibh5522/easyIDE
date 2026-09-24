package dev.easyide.app.ui.screens.golden

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.screens.onboarding.EnvironmentSetupContent
import dev.easyide.app.ui.screens.onboarding.EnvironmentSetupState
import dev.easyide.app.ui.screens.onboarding.OnboardingFrame
import dev.easyide.app.ui.screens.onboarding.OnboardingLayout
import dev.easyide.app.ui.screens.onboarding.OnboardingStep
import dev.easyide.app.ui.screens.onboarding.OnboardingSteps
import dev.easyide.app.ui.screens.onboarding.StepButton
import dev.easyide.app.ui.screens.onboarding.StepButtons
import dev.easyide.app.ui.screens.onboarding.StepPaneSpec
import dev.easyide.app.ui.screens.onboarding.WelcomeBody
import dev.easyide.app.ui.screens.onboarding.onboardingLayout
import dev.easyide.sandbox.model.SandboxImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of the onboarding flow at the three window configurations (density.md 4). Two panes need a
 * tablet or a wide, short window, so the medium one is 720dp wide and 400dp high, as a phone in
 * landscape. Record: `./gradlew :app:recordRoborazziDebug --tests '*OnboardingGoldenTest'`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OnboardingGoldenTest {

    private companion object {
        const val TABLET = "w1152dp-h800dp-xhdpi"
        const val LANDSCAPE = "w720dp-h400dp-xhdpi"
        const val PHONE = "w411dp-h891dp-xhdpi"
    }

    @get:Rule val compose = createComposeRule()

    private val flow = OnboardingSteps(sdkInt = 34)
    private val images = listOf(
        SandboxImage("ubuntu", "Ubuntu 24.04", "General purpose, apt, the largest package set.", emptyMap()),
        SandboxImage("alpine", "Alpine", "Small and fast to install; musl instead of glibc.", emptyMap()),
        SandboxImage("debian", "Debian 12", "Stable and conservative.", emptyMap()),
    )

    private fun pane(buttons: StepButtons) = StepPaneSpec(buttons, {}, { true }, {})

    @Composable
    private fun Welcome(layout: OnboardingLayout) =
        OnboardingFrame(flow, OnboardingStep.WELCOME, layout, null, pane(StepButtons(StepButton.GET_STARTED, null)), null) {
            WelcomeBody(showMark = layout == OnboardingLayout.SINGLE_COLUMN, alreadyTyped = true, onTyped = {})
        }

    @Composable
    private fun Environment(layout: OnboardingLayout) =
        OnboardingFrame(flow, OnboardingStep.ENVIRONMENT, layout, {}, pane(StepButtons(null, StepButton.SKIP_ENVIRONMENT)), null) {
            EnvironmentSetupContent(EnvironmentSetupState(images, "ubuntu"), {}, {}, {}, {})
        }

    private fun welcome() = compose.goldenShot { Welcome(onboardingLayout(LocalWindowSize.current)) }
    private fun environment() = compose.goldenShot { Environment(onboardingLayout(LocalWindowSize.current)) }

    @Test @Config(qualifiers = TABLET) fun welcomeExpandedDense() = welcome()
    @Test @Config(qualifiers = LANDSCAPE) fun welcomeMediumDense() = welcome()
    @Test @Config(qualifiers = PHONE) fun welcomeCompactComfortable() = welcome()

    @Test @Config(qualifiers = TABLET) fun environmentExpandedDense() = environment()
    @Test @Config(qualifiers = LANDSCAPE) fun environmentMediumDense() = environment()
    @Test @Config(qualifiers = PHONE) fun environmentCompactComfortable() = environment()
}
