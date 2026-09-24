package dev.easyide.app.ui.screens.onboarding

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.components.ChoiceCard
import dev.easyide.app.ui.theme.EasyIdeFonts
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.sandbox.bootstrap.InstallEvent

/**
 * The body of the "first environment" step, shared by onboarding and the
 * standalone Install Linux screen so the two cannot drift apart. It shows only
 * content; the surrounding screen owns Back / Skip / Continue.
 */
@Composable
fun EnvironmentSetupContent(
    state: EnvironmentSetupState,
    onImageSelected: (String) -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onChooseAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        when (val stage = state.stage) {
            SetupStage.Choosing -> Choosing(state, onImageSelected, onInstall)
            is SetupStage.Installing -> Installing(stage, onCancel)
            is SetupStage.Failed -> Failed(stage, onInstall, onChooseAnother)
            is SetupStage.Ready -> Ready(stage)
        }
    }
}

@Composable
private fun Choosing(state: EnvironmentSetupState, onImageSelected: (String) -> Unit, onInstall: () -> Unit) {
    Text(
        text = stringResource(R.string.setup_choose_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        state.images.forEach { image ->
            ChoiceCard(
                selected = image.id == state.selectedImageId,
                title = image.label,
                body = image.description,
                onClick = { onImageSelected(image.id) },
            )
        }
    }
    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_install)) }
}

@Composable
private fun Installing(stage: SetupStage.Installing, onCancel: () -> Unit) {
    val context = LocalContext.current
    val phase = stage.event.phaseText { Formatter.formatShortFileSize(context, it) }
    Text(stringResource(R.string.setup_installing_title, stage.imageLabel), style = MaterialTheme.typography.titleMedium)

    val progress = stage.fraction
    val description = stringResource(R.string.setup_progress_description, phase)
    val barModifier = Modifier.fillMaxWidth().semantics { stateDescription = description }
    if (progress != null) {
        LinearProgressIndicator(progress = { progress }, modifier = barModifier)
    } else {
        LinearProgressIndicator(modifier = barModifier)
    }
    Text(phase, style = MaterialTheme.typography.bodyMedium)

    // Package-install output is the only sign of life during a long apt step.
    if (stage.event is InstallEvent.Setup && stage.lastLine != null) {
        Text(
            text = stage.lastLine,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = EasyIdeFonts.mono),
            color = editorColors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Text(
        text = stringResource(R.string.setup_installing_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_cancel))
    }
}

@Composable
private fun Failed(stage: SetupStage.Failed, onRetry: () -> Unit, onChooseAnother: () -> Unit) {
    val failure = stage.failure
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(IconSize.l),
        )
        Text(stringResource(failure.kind.title()), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(failure.kind.advice()), style = MaterialTheme.typography.bodyMedium)
        failure.detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = EasyIdeFonts.mono),
                color = editorColors.textMuted,
            )
        }
    }
    if (failure.kind.retryable) {
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(failure.kind.retryLabel()))
        }
    }
    OutlinedButton(onClick = onChooseAnother, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_choose_another))
    }
}

@Composable
private fun Ready(stage: SetupStage.Ready) {
    Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = editorColors.success,
            modifier = Modifier.size(IconSize.l),
        )
        Text(
            text = stage.imageLabel?.let { stringResource(R.string.setup_ready_title, it) }
                ?: stringResource(R.string.setup_already_installed_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(stringResource(R.string.setup_ready_body), style = MaterialTheme.typography.bodyMedium)
    }
}

/** One sentence for what the install is doing right now. [size] formats a byte count for display. */
@Composable
private fun InstallEvent?.phaseText(size: (Long) -> String): String = when (this) {
    null, InstallEvent.PreparingRuntime -> stringResource(R.string.setup_phase_preparing)
    is InstallEvent.Downloading -> {
        val total = totalBytes
        when {
            total != null && resumedFrom > 0 ->
                stringResource(R.string.setup_phase_downloading_resumed, size(bytes), size(total), size(resumedFrom))
            total != null -> stringResource(R.string.setup_phase_downloading, size(bytes), size(total))
            else -> stringResource(R.string.setup_phase_downloading_unknown, size(bytes))
        }
    }
    is InstallEvent.Extracting -> stringResource(R.string.setup_phase_extracting)
    is InstallEvent.Setup -> stringResource(R.string.setup_phase_setup, step, stepCount)
}

private fun InstallFailure.title(): Int = when (this) {
    InstallFailure.NETWORK -> R.string.setup_fail_network_title
    InstallFailure.SERVER -> R.string.setup_fail_server_title
    InstallFailure.CORRUPT_DOWNLOAD -> R.string.setup_fail_corrupt_title
    InstallFailure.STORAGE_FULL -> R.string.setup_fail_storage_full_title
    InstallFailure.STORAGE -> R.string.setup_fail_storage_title
    InstallFailure.UNSUPPORTED_DEVICE -> R.string.setup_fail_unsupported_title
    InstallFailure.SETUP_STEP -> R.string.setup_fail_setup_title
    InstallFailure.UNKNOWN -> R.string.setup_fail_unknown_title
}

private fun InstallFailure.advice(): Int = when (this) {
    InstallFailure.NETWORK -> R.string.setup_fail_network_advice
    InstallFailure.SERVER -> R.string.setup_fail_server_advice
    InstallFailure.CORRUPT_DOWNLOAD -> R.string.setup_fail_corrupt_advice
    InstallFailure.STORAGE_FULL -> R.string.setup_fail_storage_full_advice
    InstallFailure.STORAGE -> R.string.setup_fail_storage_advice
    InstallFailure.UNSUPPORTED_DEVICE -> R.string.setup_fail_unsupported_advice
    InstallFailure.SETUP_STEP -> R.string.setup_fail_setup_advice
    InstallFailure.UNKNOWN -> R.string.setup_fail_unknown_advice
}

/** A dropped connection resumes where it stopped, which is worth saying on the button. */
private fun InstallFailure.retryLabel(): Int =
    if (this == InstallFailure.NETWORK) R.string.setup_resume else R.string.setup_retry
