package dev.easyide.app.ui.screens.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.ui.components.EmptyState
import dev.easyide.app.ui.foundation.MotionTokens
import dev.easyide.app.ui.foundation.motionSpec
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors

/**
 * First-run flow: Welcome, battery exemption, notifications (Android 13+), the
 * first Linux environment, Done. Every step after Welcome can be skipped and the
 * flow still finishes; it is only reported complete ([onFinished]) from the last
 * step, so quitting half-way shows onboarding again next launch.
 *
 * The two permissions are requested for a concrete reason each (see the copy):
 * the sandbox must keep running when the screen is off, and the service that
 * keeps it running needs its notification.
 */
@Composable
fun OnboardingScreen(
    setup: EnvironmentSetupViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val flow = remember { OnboardingSteps(Build.VERSION.SDK_INT) }
    var step by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME) }
    val setupState by setup.state.collectAsStateWithLifecycle()
    val installing = setupState.stage is SetupStage.Installing
    val status by rememberDeviceStatus()

    val goBack: () -> Unit = {
        if (installing) setup.cancel()
        flow.previous(step)?.let { step = it }
    }
    BackHandler(enabled = flow.previous(step) != null, onBack = goBack)

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = MAX_CONTENT_WIDTH)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            StepIndicator(position = flow.position(step), count = flow.steps.size)

            Crossfade(targetState = step, animationSpec = motionSpec(MotionTokens.DURATION_LONG_MS), label = "onboarding-step") { current ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                    when (current) {
                        OnboardingStep.WELCOME -> WelcomeStep(onNext = { step = flow.next(current) })
                        OnboardingStep.BATTERY -> BatteryStep(
                            unrestricted = status.batteryUnrestricted,
                            onBack = goBack,
                            onNext = { step = flow.next(current) },
                        )
                        OnboardingStep.NOTIFICATIONS -> NotificationsStep(
                            allowed = status.notificationsAllowed,
                            onBack = goBack,
                            onNext = { step = flow.next(current) },
                        )
                        OnboardingStep.ENVIRONMENT -> EnvironmentStep(
                            setup = setup,
                            state = setupState,
                            onBack = goBack,
                            onNext = { step = flow.next(current) },
                        )
                        OnboardingStep.DONE -> DoneStep(
                            status = status,
                            environmentReady = setupState.stage is SetupStage.Ready,
                            onBack = goBack,
                            onFinish = onFinished,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(position: Int, count: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            text = stringResource(R.string.onboarding_step_counter, position, count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(progress = { position.toFloat() / count }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    EmptyState(
        icon = Icons.Filled.Terminal,
        title = stringResource(R.string.onboarding_title),
        body = stringResource(R.string.onboarding_body),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onboarding_continue)) }
    Text(
        text = stringResource(R.string.onboarding_footnote),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BatteryStep(unrestricted: Boolean, onBack: () -> Unit, onNext: () -> Unit) {
    val context = LocalContext.current
    PermissionPage(
        icon = Icons.Filled.BatteryChargingFull,
        title = R.string.onboarding_battery_title,
        body = R.string.onboarding_battery_body,
        granted = unrestricted,
        grantedText = R.string.onboarding_battery_allowed,
        pendingText = R.string.onboarding_battery_not_allowed,
        actionLabel = R.string.onboarding_battery_allow,
        onAction = { DeviceAccess.requestBatteryExemption(context) },
        onBack = onBack,
        onNext = onNext,
    )
}

@Composable
private fun NotificationsStep(allowed: Boolean, onBack: () -> Unit, onNext: () -> Unit) {
    val context = LocalContext.current
    // Once the system prompt has been refused it may never show again, so the button
    // then opens the app's notification settings instead of asking in vain.
    var refused by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) refused = true
    }
    PermissionPage(
        icon = Icons.Filled.Notifications,
        title = R.string.onboarding_notifications_title,
        body = R.string.onboarding_notifications_body,
        granted = allowed,
        grantedText = R.string.onboarding_notifications_allowed,
        pendingText = if (refused) R.string.onboarding_notifications_refused else R.string.onboarding_notifications_not_allowed,
        actionLabel = if (refused) R.string.onboarding_notifications_open_settings else R.string.onboarding_notifications_allow,
        onAction = {
            if (refused) DeviceAccess.openNotificationSettings(context) else request.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onBack = onBack,
        onNext = onNext,
    )
}

@Composable
private fun EnvironmentStep(
    setup: EnvironmentSetupViewModel,
    state: EnvironmentSetupState,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val installing = state.stage is SetupStage.Installing
    val ready = state.stage is SetupStage.Ready

    StepHeading(R.string.onboarding_environment_title)
    EnvironmentSetupContent(
        state = state,
        onImageSelected = setup::onImageSelected,
        onInstall = setup::install,
        onCancel = setup::cancel,
        onChooseAnother = setup::backToChoosing,
    )
    if (!installing) {
        NavRow(
            onBack = onBack,
            primary = if (ready) R.string.onboarding_next else null,
            onPrimary = onNext,
            skip = if (ready) null else R.string.onboarding_skip_environment,
            onSkip = onNext,
        )
    }
}

@Composable
private fun DoneStep(status: DeviceStatus, environmentReady: Boolean, onBack: () -> Unit, onFinish: () -> Unit) {
    StepHeading(R.string.onboarding_done_title)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        SummaryRow(status.batteryUnrestricted, R.string.onboarding_done_battery_on, R.string.onboarding_done_battery_off)
        if (Build.VERSION.SDK_INT >= OnboardingSteps.NOTIFICATION_PERMISSION_MIN_SDK) {
            SummaryRow(status.notificationsAllowed, R.string.onboarding_done_notifications_on, R.string.onboarding_done_notifications_off)
        }
        SummaryRow(environmentReady, R.string.onboarding_done_linux_on, R.string.onboarding_done_linux_off)
    }
    Text(
        text = stringResource(R.string.onboarding_done_settings_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    NavRow(onBack = onBack, primary = R.string.onboarding_finish, onPrimary = onFinish, skip = null, onSkip = {})
}

/** A permission step: what it is for, whether it is granted now, and the request/continue/skip buttons. */
@Composable
private fun PermissionPage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: Int,
    body: Int,
    granted: Boolean,
    grantedText: Int,
    pendingText: Int,
    actionLabel: Int,
    onAction: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    EmptyState(
        icon = icon,
        title = stringResource(title),
        body = stringResource(body),
        modifier = Modifier.fillMaxWidth(),
    )
    StatusLine(granted, stringResource(if (granted) grantedText else pendingText))
    NavRow(
        onBack = onBack,
        primary = if (granted) R.string.onboarding_next else actionLabel,
        onPrimary = if (granted) onNext else onAction,
        skip = if (granted) null else R.string.onboarding_skip,
        onSkip = onNext,
    )
}

@Composable
private fun StepHeading(title: Int) {
    Text(text = stringResource(title), style = MaterialTheme.typography.headlineSmall)
}

/** Back on the left; skip and the primary action on the right. A null label hides that button. */
@Composable
private fun NavRow(onBack: () -> Unit, primary: Int?, onPrimary: () -> Unit, skip: Int?, onSkip: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        Box(Modifier.weight(1f))
        skip?.let { TextButton(onClick = onSkip) { Text(stringResource(it)) } }
        primary?.let { Button(onClick = onPrimary) { Text(stringResource(it)) } }
    }
}

/** Onboarding is a form, not a dashboard: capped so lines stay readable on an expanded tablet. */
private val MAX_CONTENT_WIDTH = 520.dp

/** Whether a permission is in place right now: the check mark is decoration, the words carry the state. */
@Composable
private fun StatusLine(granted: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (granted) editorColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.l),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SummaryRow(done: Boolean, doneText: Int, pendingText: Int) =
    StatusLine(done, stringResource(if (done) doneText else pendingText))
