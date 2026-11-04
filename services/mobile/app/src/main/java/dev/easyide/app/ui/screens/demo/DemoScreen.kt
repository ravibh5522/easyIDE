package dev.easyide.app.ui.screens.demo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.ui.components.FlowFrame
import dev.easyide.app.ui.components.ProseText
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.Tone

/**
 * The quick demo shown once after onboarding: pick Python or Node.js and a one-file project opens with
 * its program run in the terminal. The Linux install may still be going: the rows wait for it (the install
 * bar under the app shows how far) instead of the demo blocking anything - Skip is always there.
 */
@Composable
fun DemoScreen(
    viewModel: DemoViewModel,
    onOpenProject: (String) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onSkip)
    LaunchedEffect(state.openProjectId) { state.openProjectId?.let(onOpenProject) }

    FlowFrame(
        title = stringResource(R.string.demo_title),
        onBack = onSkip,
        modifier = modifier,
        footer = { KitButton(stringResource(R.string.demo_skip), onSkip, Modifier.fillMaxWidth(), KitButtonStyle.Secondary) },
    ) {
        val gutter = Modifier.padding(horizontal = Kit.control.hPad)
        ProseText(stringResource(R.string.demo_body), gutter.padding(vertical = Kit.space.m))
        if (!state.linuxReady) KitBanner(stringResource(R.string.demo_waiting), gutter, tone = Tone.Info)
        if (state.failed) KitBanner(stringResource(R.string.demo_failed), gutter, tone = Tone.Danger)
        KitSection(stringResource(R.string.demo_header)) {
            Demos.ALL.forEach { demo ->
                KitRow(
                    title = stringResource(demo.title),
                    subtitle = stringResource(demo.subtitle),
                    enabled = state.linuxReady && !state.busy,
                    onClick = { viewModel.start(demo) },
                    id = "demo:${demo.id}",
                )
            }
        }
    }
}
