package dev.easyide.app.ui.screens.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitScaffold
import dev.easyide.app.ui.kit.KitStepper
import dev.easyide.app.ui.kit.cropCorners
import dev.easyide.app.ui.kit.dialogActionOrder

/**
 * The chrome of the flow: the kit's title bar with the stepper, then either one column or, per
 * [layout], the rail beside the step. [body] draws one step's content; [onBack] is null on the first step.
 */
@Composable
internal fun OnboardingFrame(
    flow: OnboardingSteps,
    step: OnboardingStep,
    layout: OnboardingLayout,
    onBack: (() -> Unit)?,
    pane: StepPaneSpec,
    summary: InstallSummary?,
    modifier: Modifier = Modifier,
    body: @Composable (OnboardingStep) -> Unit,
) {
    val twoPane = layout == OnboardingLayout.TWO_PANE
    KitScaffold(
        title = stringResource(R.string.flow_rail_header),
        modifier = modifier,
        onBack = onBack,
        actions = { KitStepper(flow.steps.size, flow.steps.indexOf(step), Modifier.padding(end = Kit.space.m)) },
        maxContentWidth = if (twoPane) Dp.Infinity else Kit.contentMax,
    ) { inset ->
        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                OnboardingRail(railItems(flow, step), step, summary, Modifier.weight(OnboardingWeights.RAIL).fillMaxHeight())
                StepPane(step, pane, inset, true, body, Modifier.weight(OnboardingWeights.STEP).fillMaxHeight().dividerAtStart())
            }
        } else {
            StepPane(step, pane, inset, false, body, Modifier.fillMaxSize())
        }
    }
}

/** What the step pane needs beyond the step itself: its buttons, and the once-only typing memory. */
internal class StepPaneSpec(
    val buttons: StepButtons,
    val onButton: (StepButton) -> Unit,
    val typed: (OnboardingStep) -> Boolean,
    val onTyped: (OnboardingStep) -> Unit,
)

/** The step card (crop corners, scrolls if it must) above the actions pinned in the thumb zone. */
@Composable
private fun StepPane(
    step: OnboardingStep,
    spec: StepPaneSpec,
    inset: PaddingValues,
    centered: Boolean,
    body: @Composable (OnboardingStep) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = if (centered) Alignment.Center else Alignment.TopStart) {
            Column(Modifier.widthIn(max = Kit.contentMax).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Kit.space.l).cropCorners()) {
                Crossfade(step, animationSpec = tween(Kit.motion.standardMs), label = "onboarding-step") { current ->
                    Column(Modifier.padding(bottom = Kit.space.l)) { body(current) }
                }
            }
        }
        StepActions(spec.buttons, spec.onButton, Modifier.padding(inset).padding(Kit.space.l))
    }
}

/** Skip on the far side from the thumb's rest, the one filled action next to it; nothing at all while installing. */
@Composable
private fun StepActions(buttons: StepButtons, onButton: (StepButton) -> Unit, modifier: Modifier) {
    val ordered = dialogActionOrder(buttons.skip, buttons.primary, Kit.feel.handedness)
    if (ordered.isEmpty()) return
    Row(modifier.fillMaxWidth(), Arrangement.spacedBy(Kit.space.s, Alignment.End)) {
        ordered.forEach { button ->
            val style = if (button == buttons.primary) KitButtonStyle.Primary else KitButtonStyle.Ghost
            KitButton(stringResource(button.label), { onButton(button) }, style = style)
        }
    }
}

/** A hairline along the start edge, separating the rail from the step without a shadow. */
@Composable
private fun Modifier.dividerAtStart(): Modifier {
    val color = Kit.colors.panelBorder
    val width = Kit.hairline
    return drawBehind { drawRect(color, Offset.Zero, Size(width.toPx(), size.height)) }
}

