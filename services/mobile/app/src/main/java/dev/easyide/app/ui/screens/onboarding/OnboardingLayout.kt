package dev.easyide.app.ui.screens.onboarding

import androidx.annotation.StringRes
import dev.easyide.app.R
import dev.easyide.app.ui.foundation.HeightClass
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.foundation.WindowSize
import dev.easyide.app.ui.kit.StepState
import dev.easyide.app.ui.kit.stepState

/** Whether the flow is one column or a side rail next to the step (layout-spec: wide windows are list-detail). */
internal enum class OnboardingLayout { SINGLE_COLUMN, TWO_PANE }

/**
 * A tablet in either orientation (expanded width) and a phone in landscape (medium width, short
 * height) have room for the rail and would waste half the screen on one centred column. A phone in
 * portrait and a small tablet in portrait (compact or medium width, regular height) keep one column.
 * At 1800x2880 px and 2880x1800 px on a 2.0-density tablet the windows are 900x1440dp and
 * 1440x900dp, both expanded.
 */
internal fun onboardingLayout(size: WindowSize): OnboardingLayout = when {
    size.width == WidthClass.EXPANDED -> OnboardingLayout.TWO_PANE
    size.width == WidthClass.MEDIUM && size.height == HeightClass.COMPACT -> OnboardingLayout.TWO_PANE
    else -> OnboardingLayout.SINGLE_COLUMN
}

/** Shares of the row: the rail is the identity and progress, the step gets the larger part. */
internal object OnboardingWeights {
    const val RAIL = 0.32f
    const val STEP = 0.68f
}

@get:StringRes
internal val OnboardingStep.label: Int
    get() = when (this) {
        OnboardingStep.WELCOME -> R.string.flow_step_welcome
        OnboardingStep.BATTERY -> R.string.flow_step_battery
        OnboardingStep.NOTIFICATIONS -> R.string.flow_step_notifications
        OnboardingStep.ENVIRONMENT -> R.string.flow_step_environment
        OnboardingStep.DONE -> R.string.flow_step_done
    }

/** One line of the rail: a step and where the user is relative to it. */
internal data class RailItem(val step: OnboardingStep, val state: StepState)

/** Every applicable step in order, each marked done, current or upcoming; the same mapping the stepper draws. */
internal fun railItems(flow: OnboardingSteps, current: OnboardingStep): List<RailItem> {
    val at = flow.steps.indexOf(current)
    return flow.steps.mapIndexed { i, step -> RailItem(step, stepState(i, at)) }
}
