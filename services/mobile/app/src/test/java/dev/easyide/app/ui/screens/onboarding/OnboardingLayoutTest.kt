package dev.easyide.app.ui.screens.onboarding

import dev.easyide.app.ui.foundation.HeightClass
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.foundation.WindowSize
import dev.easyide.app.ui.kit.StepState
import dev.easyide.app.ui.screens.onboarding.OnboardingLayout.SINGLE_COLUMN
import dev.easyide.app.ui.screens.onboarding.OnboardingLayout.TWO_PANE
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingLayoutTest {

    private fun layout(width: WidthClass, height: HeightClass) = onboardingLayout(WindowSize(width, height))

    @Test fun `a 10 inch tablet is two panes in portrait and landscape`() {
        // 1800x2880 px at 2.0 density is 900x1440dp (expanded, regular); 2880x1800 px is 1440x900dp (expanded, regular).
        assertEquals(TWO_PANE, layout(WidthClass.EXPANDED, HeightClass.REGULAR))
    }

    @Test fun `a phone in landscape is two panes because its height is short`() {
        // 800x360dp: medium width, compact height.
        assertEquals(TWO_PANE, layout(WidthClass.MEDIUM, HeightClass.COMPACT))
    }

    @Test fun `a phone in portrait and a small tablet in portrait keep one column`() {
        assertEquals(SINGLE_COLUMN, layout(WidthClass.COMPACT, HeightClass.REGULAR))
        assertEquals(SINGLE_COLUMN, layout(WidthClass.MEDIUM, HeightClass.REGULAR))
    }

    @Test fun `a narrow split-screen pane is one column even on a tablet`() {
        assertEquals(SINGLE_COLUMN, layout(WidthClass.COMPACT, HeightClass.COMPACT))
    }

    @Test fun `the two panes share the whole row`() {
        assertEquals(1f, OnboardingWeights.RAIL + OnboardingWeights.STEP, 0.0001f)
    }

    @Test fun `the rail marks steps before the current one done and after it upcoming`() {
        val flow = OnboardingSteps(sdkInt = 34)
        val items = railItems(flow, OnboardingStep.NOTIFICATIONS)
        assertEquals(flow.steps, items.map { it.step })
        assertEquals(
            listOf(StepState.Done, StepState.Done, StepState.Current, StepState.Upcoming, StepState.Upcoming),
            items.map { it.state },
        )
    }

    @Test fun `the rail leaves out the notification step below Android 13`() {
        val items = railItems(OnboardingSteps(sdkInt = 30), OnboardingStep.WELCOME)
        assertEquals(listOf(StepState.Current, StepState.Upcoming, StepState.Upcoming, StepState.Upcoming), items.map { it.state })
    }
}
