package dev.easyide.app.ui.screens.onboarding

import dev.easyide.app.ui.screens.onboarding.OnboardingStep.BATTERY
import dev.easyide.app.ui.screens.onboarding.OnboardingStep.DONE
import dev.easyide.app.ui.screens.onboarding.OnboardingStep.ENVIRONMENT
import dev.easyide.app.ui.screens.onboarding.OnboardingStep.NOTIFICATIONS
import dev.easyide.app.ui.screens.onboarding.OnboardingStep.WELCOME
import dev.easyide.app.ui.screens.onboarding.StepButton.ALLOW_BATTERY
import dev.easyide.app.ui.screens.onboarding.StepButton.ALLOW_NOTIFICATIONS
import dev.easyide.app.ui.screens.onboarding.StepButton.CONTINUE
import dev.easyide.app.ui.screens.onboarding.StepButton.FINISH
import dev.easyide.app.ui.screens.onboarding.StepButton.GET_STARTED
import dev.easyide.app.ui.screens.onboarding.StepButton.OPEN_NOTIFICATION_SETTINGS
import dev.easyide.app.ui.screens.onboarding.StepButton.SKIP
import dev.easyide.app.ui.screens.onboarding.StepButton.SKIP_ENVIRONMENT
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingButtonsTest {

    @Test fun `welcome offers only get started and done only finish`() {
        assertEquals(StepButtons(GET_STARTED, null), stepButtons(WELCOME, StepFacts()))
        assertEquals(StepButtons(FINISH, null), stepButtons(DONE, StepFacts()))
    }

    @Test fun `battery asks until allowed and then continues`() {
        assertEquals(StepButtons(ALLOW_BATTERY, SKIP), stepButtons(BATTERY, StepFacts(granted = false)))
        assertEquals(StepButtons(CONTINUE, null), stepButtons(BATTERY, StepFacts(granted = true)))
    }

    @Test fun `notifications ask, then open settings once the prompt was refused, then continue when granted`() {
        assertEquals(StepButtons(ALLOW_NOTIFICATIONS, SKIP), stepButtons(NOTIFICATIONS, StepFacts()))
        assertEquals(StepButtons(OPEN_NOTIFICATION_SETTINGS, SKIP), stepButtons(NOTIFICATIONS, StepFacts(notificationsRefused = true)))
        assertEquals(StepButtons(CONTINUE, null), stepButtons(NOTIFICATIONS, StepFacts(granted = true, notificationsRefused = true)))
    }

    @Test fun `the environment step hides both buttons while installing`() {
        assertEquals(StepButtons(null, null), stepButtons(ENVIRONMENT, StepFacts(installing = true)))
    }

    @Test fun `the environment step continues when ready and can be skipped otherwise`() {
        assertEquals(StepButtons(CONTINUE, null), stepButtons(ENVIRONMENT, StepFacts(environmentReady = true)))
        assertEquals(StepButtons(null, SKIP_ENVIRONMENT), stepButtons(ENVIRONMENT, StepFacts()))
    }

    @Test fun `every step has exactly one primary button unless it is installing`() {
        val all = OnboardingSteps(34).steps
        all.forEach { step -> assertEquals(step.name, step != ENVIRONMENT, stepButtons(step, StepFacts()).primary != null) }
    }
}
