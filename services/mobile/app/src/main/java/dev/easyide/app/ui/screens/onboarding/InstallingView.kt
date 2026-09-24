package dev.easyide.app.ui.screens.onboarding

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.easyide.app.R
import dev.easyide.app.ui.components.MonoText
import dev.easyide.app.ui.components.ProseText
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitGroup
import dev.easyide.app.ui.kit.KitProgress
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.sandbox.bootstrap.InstallEvent

/** The latest package-install line is the only sign of life during a long apt step; a long one wraps to this many lines. */
private const val LOG_LINE_MAX_LINES = 2

/**
 * The running install: a cell-fill bar for the overall fraction (a sweeping cursor while it cannot
 * be known), the phase in mono, the tail of the package log and Cancel. Progress is real: every value
 * comes from the installer's events.
 */
@Composable
internal fun InstallingView(stage: SetupStage.Installing, onCancel: () -> Unit) {
    val phase = installPhaseText(stage.event)
    val space = Kit.space
    val description = stringResource(R.string.setup_progress_description, phase)
    Column(Modifier.padding(horizontal = space.l), verticalArrangement = Arrangement.spacedBy(space.m)) {
        BasicText(
            stringResource(R.string.setup_installing_title, stage.imageLabel),
            style = Kit.text.heading.copy(color = Kit.colors.plainText),
        )
        KitProgress(stage.fraction, Modifier.fillMaxWidth().kitTag("install-progress").semantics { stateDescription = description })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MonoText(phase, Modifier.weight(1f), maxLines = 2)
            stage.fraction?.let { MonoText(stringResource(R.string.kit_progress_percent, (it * PERCENT).toInt())) }
        }
        if (stage.event is InstallEvent.Setup && stage.lastLine != null) {
            KitGroup { MonoText(stage.lastLine, Modifier.padding(space.s), maxLines = LOG_LINE_MAX_LINES) }
        }
        ProseText(stringResource(R.string.setup_installing_note), muted = true)
        KitButton(stringResource(R.string.action_cancel), onCancel, Modifier.fillMaxWidth(), KitButtonStyle.Secondary)
    }
}

private const val PERCENT = 100

/** One sentence for what the install is doing right now, with byte counts formatted for the device's locale. */
@Composable
internal fun installPhaseText(event: InstallEvent?): String {
    val context = LocalContext.current
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
    return when (event) {
        null, InstallEvent.PreparingRuntime -> stringResource(R.string.setup_phase_preparing)
        is InstallEvent.Downloading -> {
            val total = event.totalBytes
            when {
                total != null && event.resumedFrom > 0 ->
                    stringResource(R.string.setup_phase_downloading_resumed, size(event.bytes), size(total), size(event.resumedFrom))
                total != null -> stringResource(R.string.setup_phase_downloading, size(event.bytes), size(total))
                else -> stringResource(R.string.setup_phase_downloading_unknown, size(event.bytes))
            }
        }
        is InstallEvent.Extracting -> stringResource(R.string.setup_phase_extracting)
        is InstallEvent.Setup -> stringResource(R.string.setup_phase_setup, event.step, event.stepCount)
    }
}
