package dev.easyide.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.components.MonoText
import dev.easyide.app.ui.components.ProseText
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitProgress
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.StepState
import dev.easyide.app.ui.kit.Tone

/** What the rail shows about a running install: the bar and the phase, so progress stays in view beside the step. */
internal data class InstallSummary(val fraction: Float?, val phase: String)

/**
 * The left pane on wide windows: the mark, one sentence about the app, every step with where the
 * user is, and, while Linux installs, its progress. It is read-only; the step pane owns every action.
 */
@Composable
internal fun OnboardingRail(
    items: List<RailItem>,
    current: OnboardingStep,
    install: InstallSummary?,
    modifier: Modifier = Modifier,
) {
    val gutter = Modifier.padding(horizontal = Kit.control.hPad)
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(vertical = Kit.space.l),
        verticalArrangement = Arrangement.spacedBy(Kit.space.m),
    ) {
        Mark(blinking = current == OnboardingStep.WELCOME, modifier = gutter)
        ProseText(stringResource(R.string.onboarding_body), gutter, muted = true)
        KitSection(stringResource(R.string.flow_rail_header)) {
            items.forEach { item ->
                KitRow(
                    title = stringResource(item.step.label),
                    selected = item.state == StepState.Current,
                    trailing = { RailTag(item.state) },
                    id = "rail-${item.step.name.lowercase()}",
                )
            }
        }
        if (install != null) InstallSummaryBlock(install, gutter)
    }
}

@Composable
private fun RailTag(state: StepState) {
    when (state) {
        StepState.Done -> KitTag(stringResource(R.string.flow_tag_done))
        StepState.Current -> KitTag(stringResource(R.string.flow_tag_now), tone = Tone.Accent, selected = true)
        StepState.Upcoming -> Unit
    }
}

@Composable
private fun InstallSummaryBlock(install: InstallSummary, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
        KitProgress(install.fraction, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MonoText(install.phase, Modifier.weight(1f))
            install.fraction?.let { MonoText(stringResource(R.string.kit_progress_percent, (it * PERCENT).toInt())) }
        }
    }
}

private const val PERCENT = 100
