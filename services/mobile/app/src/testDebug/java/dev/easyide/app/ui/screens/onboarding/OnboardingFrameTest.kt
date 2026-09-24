package dev.easyide.app.ui.screens.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.foundation.HeightClass
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.foundation.WindowSize
import dev.easyide.app.ui.theme.EasyIdeTheme
import dev.easyide.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The onboarding frame at the window sizes of the acceptance matrix (layout-spec 10), rendered on the
 * JVM: a 10 inch tablet at 1800x2880 px portrait and 2880x1800 px landscape (900x1440dp, 1440x900dp
 * at 2.0 density), a phone in portrait and a phone in landscape. Checks structure and bounds, not pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// SDK 35, not compileSdk 37: see KitSemanticsTest.
@Config(sdk = [35])
class OnboardingFrameTest {

    @get:Rule val compose = createComposeRule()

    private val flow = OnboardingSteps(sdkInt = 34)
    private val pane = StepPaneSpec(StepButtons(StepButton.GET_STARTED, null), {}, { false }, {})

    private fun showWelcome(width: WidthClass, height: HeightClass) {
        val size = WindowSize(width, height)
        val layout = onboardingLayout(size)
        compose.setContent {
            EasyIdeTheme(themeMode = ThemeMode.DARK) {
                CompositionLocalProvider(LocalWindowSize provides size) {
                    Frame(layout)
                }
            }
        }
    }

    @Composable
    private fun Frame(layout: OnboardingLayout) =
        OnboardingFrame(flow, OnboardingStep.WELCOME, layout, null, pane, null) {
            WelcomeBody(showMark = layout == OnboardingLayout.SINGLE_COLUMN, alreadyTyped = true, onTyped = {})
        }

    private fun assertRailBesideStep(windowWidth: Dp, windowHeight: Dp) {
        val rail = compose.onNodeWithTag("kit:rail-welcome").also { it.assertIsDisplayed() }.getBoundsInRoot()
        val button = compose.onNodeWithText("Get started").also { it.assertIsDisplayed() }.getBoundsInRoot()
        assertTrue("rail ${rail.right} must end before the step's button starts at ${button.left}", rail.right <= button.left)
        assertTrue("the rail takes under half of $windowWidth, got ${rail.right}", rail.right < windowWidth / 2)
        assertTrue("the action is in the lower part of $windowHeight, got ${button.bottom}", button.bottom > windowHeight * 0.6f)
    }

    @Test @Config(qualifiers = "w900dp-h1440dp-xhdpi")
    fun `a tablet in portrait shows the rail beside the step`() {
        showWelcome(WidthClass.EXPANDED, HeightClass.REGULAR)
        assertRailBesideStep(900.dp, 1440.dp)
    }

    @Test @Config(qualifiers = "w1440dp-h900dp-xhdpi")
    fun `a tablet in landscape shows the rail beside the step`() {
        showWelcome(WidthClass.EXPANDED, HeightClass.REGULAR)
        assertRailBesideStep(1440.dp, 900.dp)
    }

    @Test @Config(qualifiers = "w800dp-h360dp-xhdpi")
    fun `a phone in landscape shows the rail beside the step`() {
        showWelcome(WidthClass.MEDIUM, HeightClass.COMPACT)
        compose.onNodeWithTag("kit:rail-welcome").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w393dp-h851dp-xhdpi")
    fun `a phone in portrait is one column with the action in the lower part`() {
        showWelcome(WidthClass.COMPACT, HeightClass.REGULAR)
        assertEquals(0, compose.onAllNodesWithTag("kit:rail-welcome").fetchSemanticsNodes().size)
        val button = compose.onNodeWithText("Get started").also { it.assertIsDisplayed() }.getBoundsInRoot()
        assertTrue("the action is in the thumb zone, got ${button.bottom}", button.bottom > 851.dp * 0.7f)
    }
}
