package dev.easyide.app.ui.screens.onboarding

import androidx.annotation.StringRes
import dev.easyide.app.R

/** The buttons a step can offer; each maps to one label and, in the screen, one action. */
internal enum class StepButton(@StringRes val label: Int) {
    GET_STARTED(R.string.onboarding_continue),
    CONTINUE(R.string.onboarding_next),
    FINISH(R.string.onboarding_finish),
    ALLOW_BATTERY(R.string.onboarding_battery_allow),
    ALLOW_NOTIFICATIONS(R.string.onboarding_notifications_allow),
    OPEN_NOTIFICATION_SETTINGS(R.string.onboarding_notifications_open_settings),
    SKIP(R.string.onboarding_skip),
    SKIP_ENVIRONMENT(R.string.onboarding_skip_environment),
}

/** [primary] is the one filled action of the step; [skip] is the ghost one. Null hides that button. */
internal data class StepButtons(val primary: StepButton?, val skip: StepButton?)

/** What the current step's facts are: whether its permission is in place, the runtime prompt was refused, and the install state. */
internal data class StepFacts(
    val granted: Boolean = false,
    val notificationsRefused: Boolean = false,
    val installing: Boolean = false,
    val environmentReady: Boolean = false,
)

/**
 * A permission step offers Continue once granted, otherwise the request (or the settings screen
 * after the runtime prompt was refused) with Skip. The environment step hides both while installing,
 * offers Continue when ready and Skip for now otherwise; its Install button lives in its content.
 */
internal fun stepButtons(step: OnboardingStep, facts: StepFacts): StepButtons = when (step) {
    OnboardingStep.WELCOME -> StepButtons(StepButton.GET_STARTED, null)
    OnboardingStep.BATTERY ->
        if (facts.granted) StepButtons(StepButton.CONTINUE, null) else StepButtons(StepButton.ALLOW_BATTERY, StepButton.SKIP)
    OnboardingStep.NOTIFICATIONS -> when {
        facts.granted -> StepButtons(StepButton.CONTINUE, null)
        facts.notificationsRefused -> StepButtons(StepButton.OPEN_NOTIFICATION_SETTINGS, StepButton.SKIP)
        else -> StepButtons(StepButton.ALLOW_NOTIFICATIONS, StepButton.SKIP)
    }
    OnboardingStep.ENVIRONMENT -> when {
        facts.installing -> StepButtons(null, null)
        facts.environmentReady -> StepButtons(StepButton.CONTINUE, null)
        else -> StepButtons(null, StepButton.SKIP_ENVIRONMENT)
    }
    OnboardingStep.DONE -> StepButtons(StepButton.FINISH, null)
}
