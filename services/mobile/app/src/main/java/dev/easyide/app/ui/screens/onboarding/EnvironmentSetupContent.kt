package dev.easyide.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.components.MonoText
import dev.easyide.app.ui.components.ProseText
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone

/**
 * The body of the "first environment" step, shared by onboarding and the standalone Install Linux
 * screen so the two cannot drift apart. It shows only content; the surrounding screen owns
 * Back / Skip / Continue.
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Kit.space.l)) {
        when (val stage = state.stage) {
            SetupStage.Choosing -> Choosing(state, onImageSelected, onInstall)
            is SetupStage.Installing -> InstallingView(stage, onCancel)
            is SetupStage.Failed -> Failed(stage, onInstall, onChooseAnother)
            is SetupStage.Ready -> Ready(stage)
        }
    }
}

@Composable
private fun Choosing(state: EnvironmentSetupState, onImageSelected: (String) -> Unit, onInstall: () -> Unit) {
    val gutter = Modifier.padding(horizontal = Kit.space.l)
    ProseText(stringResource(R.string.setup_choose_body), gutter, muted = true)
    KitSection(stringResource(R.string.setup_presets_header), Modifier.selectableGroup()) {
        state.images.forEach { image ->
            KitRow(
                title = image.label,
                subtitle = image.description,
                selected = image.id == state.selectedImageId,
                onClick = { onImageSelected(image.id) },
                id = "preset:${image.id}",
            )
        }
    }
    KitButton(stringResource(R.string.setup_install), onInstall, gutter.fillMaxWidth())
}

@Composable
private fun Failed(stage: SetupStage.Failed, onRetry: () -> Unit, onChooseAnother: () -> Unit) {
    val failure = stage.failure
    val gutter = Modifier.padding(horizontal = Kit.space.l)
    Column(gutter, verticalArrangement = Arrangement.spacedBy(Kit.space.s)) {
        KitBanner(stringResource(failure.kind.title), tone = Tone.Danger)
        ProseText(stringResource(failure.kind.advice))
        failure.detail?.let { MonoText(it, maxLines = DETAIL_MAX_LINES) }
    }
    Column(gutter, verticalArrangement = Arrangement.spacedBy(Kit.space.s)) {
        if (failure.kind.retryable) KitButton(stringResource(failure.kind.retryLabel), onRetry, Modifier.fillMaxWidth())
        KitButton(stringResource(R.string.setup_choose_another), onChooseAnother, Modifier.fillMaxWidth(), KitButtonStyle.Secondary)
    }
}

/** A technical reason is a line or two; more is a stack trace that belongs in the log. */
private const val DETAIL_MAX_LINES = 4

@Composable
private fun Ready(stage: SetupStage.Ready) {
    KitSection(null) {
        KitRow(
            title = stage.imageLabel?.let { stringResource(R.string.setup_ready_title, it) }
                ?: stringResource(R.string.setup_already_installed_title),
            subtitle = stringResource(R.string.setup_ready_body),
            trailing = { KitTag(stringResource(R.string.flow_tag_ready), tone = Tone.Success) },
            id = "install-ready",
        )
    }
}
