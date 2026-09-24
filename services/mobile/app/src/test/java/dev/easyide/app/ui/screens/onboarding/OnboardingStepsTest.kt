package dev.easyide.app.ui.screens.onboarding

import dev.easyide.app.ui.screens.onboarding.OnboardingStep.BATTERY
import dev.easyide.app.ui.screens.onboarding.OnboardingStep.DONE
import dev.easyide.app.ui.screens.onboarding.OnboardingStep.ENVIRONMENT
import dev.easyide.app.ui.screens.onboarding.OnboardingStep.NOTIFICATIONS
import dev.easyide.app.ui.screens.onboarding.OnboardingStep.WELCOME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingStepsTest {

    private val modern = OnboardingSteps(sdkInt = 34)
    private val legacy = OnboardingSteps(sdkInt = 30)

    @Test fun `notification step exists only from Android 13`() {
        assertEquals(listOf(WELCOME, BATTERY, NOTIFICATIONS, ENVIRONMENT, DONE), modern.steps)
        assertEquals(listOf(WELCOME, BATTERY, ENVIRONMENT, DONE), legacy.steps)
        assertEquals(listOf(WELCOME, BATTERY, NOTIFICATIONS, ENVIRONMENT, DONE), OnboardingSteps(33).steps)
    }

    @Test fun `next walks forward and stays on the last step`() {
        assertEquals(BATTERY, modern.next(WELCOME))
        assertEquals(NOTIFICATIONS, modern.next(BATTERY))
        assertEquals(ENVIRONMENT, legacy.next(BATTERY))
        assertEquals(DONE, modern.next(ENVIRONMENT))
        assertEquals(DONE, modern.next(DONE))
    }

    @Test fun `previous walks back and has nowhere to go from the first step`() {
        assertEquals(ENVIRONMENT, modern.previous(DONE))
        assertEquals(BATTERY, legacy.previous(ENVIRONMENT))
        assertNull(modern.previous(WELCOME))
    }

    @Test fun `positions are one-based over the applicable steps`() {
        assertEquals(1, modern.position(WELCOME))
        assertEquals(5, modern.position(DONE))
        assertEquals(4, legacy.position(DONE))
    }
}
