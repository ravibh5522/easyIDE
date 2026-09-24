package dev.easyide.app.ui.screens.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.kit.Kit

/**
 * First-run flow: Welcome, battery exemption, notifications (Android 13+), the first Linux
 * environment, Done. Every step after Welcome can be skipped and the flow still finishes; it is only
 * reported complete ([onFinished]) from the last step, so quitting half-way shows onboarding again
 * next launch.
 *
 * The two permissions are requested for a concrete reason each (see the copy): the sandbox must keep
 * running when the screen is off, and the service that keeps it running needs its notification. Their
 * state is re-read on resume ([rememberDeviceStatus]) because both are granted on system screens.
 * On wide windows the steps sit beside a rail with the identity and progress ([onboardingLayout]).
 */
@Composable
fun OnboardingScreen(
    setup: EnvironmentSetupViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val flow = remember { OnboardingSteps(Build.VERSION.SDK_INT) }
    var step by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }
    var typedMask by rememberSaveable { mutableIntStateOf(0) }
    // Once the system prompt has been refused it may never show again, so the button
    // then opens the app's notification settings instead of asking in vain.
    var refused by rememberSaveable { mutableStateOf(false) }
    val setupState by setup.state.collectAsStateWithLifecycle()
    val status by rememberDeviceStatus()
    val context = LocalContext.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) refused = true
    }
    val installing = setupState.stage as? SetupStage.Installing

    val goBack: () -> Unit = {
        if (installing != null) setup.cancel()
        flow.previous(step)?.let { step = it }
    }
    BackHandler(enabled = flow.previous(step) != null, onBack = goBack)

    val facts = StepFacts(
        granted = when (step) {
            OnboardingStep.BATTERY -> status.batteryUnrestricted
            OnboardingStep.NOTIFICATIONS -> status.notificationsAllowed
            else -> false
        },
        notificationsRefused = refused,
        installing = installing != null,
        environmentReady = setupState.stage is SetupStage.Ready,
    )
    val onButton: (StepButton) -> Unit = { button ->
        when (button) {
            StepButton.GET_STARTED, StepButton.CONTINUE, StepButton.SKIP, StepButton.SKIP_ENVIRONMENT -> step = flow.next(step)
            StepButton.FINISH -> onFinished()
            StepButton.ALLOW_BATTERY -> DeviceAccess.requestBatteryExemption(context)
            StepButton.ALLOW_NOTIFICATIONS -> request.launch(Manifest.permission.POST_NOTIFICATIONS)
            StepButton.OPEN_NOTIFICATION_SETTINGS -> DeviceAccess.openNotificationSettings(context)
        }
    }

    val summary = installing?.let { InstallSummary(it.fraction, installPhaseText(it.event)) }
    val pane = StepPaneSpec(
        buttons = stepButtons(step, facts),
        onButton = onButton,
        typed = { (typedMask shr it.ordinal) and 1 == 1 },
        onTyped = { typedMask = typedMask or (1 shl it.ordinal) },
    )
    val layout = onboardingLayout(LocalWindowSize.current)

    OnboardingFrame(flow, step, layout, if (flow.previous(step) != null) goBack else null, pane, summary, modifier) { current ->
        StepBody(current, setup, setupState, status, refused, showMark = layout == OnboardingLayout.SINGLE_COLUMN, pane)
    }
}

@Composable
private fun StepBody(
    step: OnboardingStep,
    setup: EnvironmentSetupViewModel,
    setupState: EnvironmentSetupState,
    status: DeviceStatus,
    refused: Boolean,
    showMark: Boolean,
    pane: StepPaneSpec,
) {
    val typed = pane.typed(step)
    val onTyped = { pane.onTyped(step) }
    when (step) {
        OnboardingStep.WELCOME -> WelcomeBody(showMark, typed, onTyped)
        OnboardingStep.BATTERY -> PermissionBody(
            R.string.onboarding_battery_title, R.string.onboarding_battery_body, status.batteryUnrestricted,
            R.string.onboarding_battery_allowed, R.string.onboarding_battery_not_allowed, typed, onTyped,
        )
        OnboardingStep.NOTIFICATIONS -> PermissionBody(
            R.string.onboarding_notifications_title, R.string.onboarding_notifications_body, status.notificationsAllowed,
            R.string.onboarding_notifications_allowed,
            if (refused) R.string.onboarding_notifications_refused else R.string.onboarding_notifications_not_allowed,
            typed, onTyped,
        )
        OnboardingStep.ENVIRONMENT -> {
            Column(Modifier.padding(Kit.space.l)) { StepTitle(R.string.onboarding_environment_title, typed, onTyped) }
            EnvironmentSetupContent(
                state = setupState,
                onImageSelected = setup::onImageSelected,
                onInstall = setup::install,
                onCancel = setup::cancel,
                onChooseAnother = setup::backToChoosing,
            )
        }
        OnboardingStep.DONE -> DoneBody(status, setupState.stage is SetupStage.Ready, typed, onTyped)
    }
}
