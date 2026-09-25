package dev.easyide.app.ui.shell

import dev.easyide.app.ui.screens.workspace.layout.PaneSizes
import dev.easyide.app.ui.screens.workspace.layout.StageRule
import dev.easyide.app.ui.screens.workspace.layout.StageVisibility

/**
 * The panels of one scope in one arrangement: which container each placement shows, which panels
 * are open, their dragged sizes, and the preset that produced them. Kept per arrangement so a
 * window that changes size class or posture finds the layout it had there (shell-model.md section 12).
 *
 * Open state reuses [StageVisibility], so the "what fits at once" rules are the ones the workspace
 * already has and tests.
 */
data class PanelLayout(
    val preset: String = LayoutPresets.AUTO,
    val containers: Map<Placement, String> = emptyMap(),
    val open: StageVisibility = StageVisibility(),
    val sizes: PaneSizes = PaneSizes(),
) {
    fun isOpen(placement: Placement): Boolean = open.isVisible(placement.stage)

    fun container(placement: Placement): String? = containers[placement]

    /** Shows [container] in [placement] (when given) and opens the panel, closing whatever the rule says must yield. */
    fun show(placement: Placement, rule: StageRule, container: String? = null): PanelLayout = copy(
        containers = if (container != null) containers + (placement to container) else containers,
        open = open.show(placement.stage, rule),
    )

    fun hide(placement: Placement, rule: StageRule): PanelLayout = copy(open = open.hide(placement.stage, rule))

    fun toggle(placement: Placement, rule: StageRule): PanelLayout =
        if (isOpen(placement)) hide(placement, rule) else show(placement, rule)

    /** The layout after the window changed: what no longer fits closes. */
    fun constrain(rule: StageRule): PanelLayout = copy(open = open.constrain(rule))

    /** Closes the panels in [placements] (compact overlays that a document open dismisses). */
    fun dismiss(placements: Set<Placement>, rule: StageRule): PanelLayout =
        placements.fold(this) { l, p -> if (l.isOpen(p)) l.hide(p, rule) else l }
}
