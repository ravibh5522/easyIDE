package dev.easyide.app.ui.screens.onboarding

enum class OnboardingStep { WELCOME, BATTERY, NOTIFICATIONS, ENVIRONMENT, DONE }

/**
 * The order of first-run steps. A step that cannot apply on this device is not
 * in the list at all (rather than shown and skipped), so the "step 3 of 5"
 * counter and Back/Next never disagree with what the user sees.
 *
 * Nothing here records completion: the flow is only complete when the caller
 * has reached [OnboardingStep.DONE] and finishes, which is what lets a
 * user who quits half-way see onboarding again.
 */
class OnboardingSteps(sdkInt: Int) {

    val steps: List<OnboardingStep> = buildList {
        add(OnboardingStep.WELCOME)
        add(OnboardingStep.BATTERY)
        // POST_NOTIFICATIONS is a runtime permission only from Android 13; below that the
        // foreground-service notification is always allowed.
        if (sdkInt >= NOTIFICATION_PERMISSION_MIN_SDK) add(OnboardingStep.NOTIFICATIONS)
        add(OnboardingStep.ENVIRONMENT)
        add(OnboardingStep.DONE)
    }

    /** 1-based position, for "Step 2 of 4". */
    fun position(step: OnboardingStep): Int = steps.indexOf(step) + 1

    fun next(step: OnboardingStep): OnboardingStep = steps.getOrElse(steps.indexOf(step) + 1) { steps.last() }

    /** The step before [step], or null on the first - Back has nowhere to go. */
    fun previous(step: OnboardingStep): OnboardingStep? = steps.getOrNull(steps.indexOf(step) - 1)

    companion object {
        const val NOTIFICATION_PERMISSION_MIN_SDK = 33
    }
}
