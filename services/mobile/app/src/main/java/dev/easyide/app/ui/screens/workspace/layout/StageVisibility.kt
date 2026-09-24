package dev.easyide.app.ui.screens.workspace.layout

/** The three dockable stages (the editor is always there). */
enum class Stage { LEFT, RIGHT, BOTTOM }

/**
 * What a window can physically hold at once, derived from [PaneArrangement]. A rule is
 * enforced whenever a stage opens or the window changes, so no caller has to know which
 * combinations fit.
 */
enum class StageRule {
    FREE,

    /** Left and right panels never share a window (medium). */
    ONE_SIDE,

    /** One stage at a time over the editor (compact). */
    ONE_PANE,

    /** Book posture: the left stage owns its page of the hinge and cannot be hidden. */
    LEFT_PINNED,

    /** Tabletop posture: the bottom stage owns the lower half and cannot be hidden. */
    BOTTOM_PINNED;

    companion object {
        fun of(arrangement: PaneArrangement): StageRule = when (arrangement) {
            PaneArrangement.FULL -> FREE
            PaneArrangement.ONE_SIDE -> ONE_SIDE
            PaneArrangement.SINGLE_PANE -> ONE_PANE
            PaneArrangement.BOOK -> LEFT_PINNED
            PaneArrangement.TABLETOP -> BOTTOM_PINNED
        }
    }
}

/**
 * Which stages show, as an immutable value so the transitions are testable without a
 * composition. Every transition takes the [StageRule] and returns a state that satisfies it;
 * when two stages conflict the one being opened wins.
 */
data class StageVisibility(
    val left: Boolean = false,
    val right: Boolean = false,
    val bottom: Boolean = false,
) {
    fun isVisible(stage: Stage): Boolean = when (stage) {
        Stage.LEFT -> left
        Stage.RIGHT -> right
        Stage.BOTTOM -> bottom
    }

    fun show(stage: Stage, rule: StageRule): StageVisibility = with(stage, true).constrain(rule, keep = stage)

    fun hide(stage: Stage, rule: StageRule): StageVisibility = with(stage, false).constrain(rule)

    fun toggle(stage: Stage, rule: StageRule): StageVisibility =
        if (isVisible(stage)) hide(stage, rule) else show(stage, rule)

    /**
     * The state closest to this one that satisfies [rule]. Without a [keep] (a window resize,
     * not a user action) the priority is left, bottom, right - the explorer is what people
     * reach for first, the terminal second.
     */
    fun constrain(rule: StageRule, keep: Stage? = null): StageVisibility = when (rule) {
        StageRule.FREE -> this
        StageRule.ONE_SIDE ->
            if (left && right) (if (keep == Stage.RIGHT) copy(left = false) else copy(right = false)) else this
        StageRule.ONE_PANE -> {
            val winner = keep?.takeIf(::isVisible) ?: listOf(Stage.LEFT, Stage.BOTTOM, Stage.RIGHT).firstOrNull(::isVisible)
            StageVisibility(
                left = winner == Stage.LEFT,
                right = winner == Stage.RIGHT,
                bottom = winner == Stage.BOTTOM,
            )
        }
        StageRule.LEFT_PINNED -> copy(left = true)
        StageRule.BOTTOM_PINNED -> copy(bottom = true)
    }

    private fun with(stage: Stage, visible: Boolean): StageVisibility = when (stage) {
        Stage.LEFT -> copy(left = visible)
        Stage.RIGHT -> copy(right = visible)
        Stage.BOTTOM -> copy(bottom = visible)
    }
}
