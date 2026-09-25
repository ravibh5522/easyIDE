package dev.easyide.app.ui.screens.workspace.layout

/**
 * The four destinations of the compact bottom switcher. Files and Git are shown in a modal
 * drawer over the editor, Editor and Terminal take the whole window.
 *
 * The stage state stays the source of truth (the extension host and the commands toggle
 * stages, not panes), so the pane is derived from it rather than stored a second time.
 */
enum class CompactPane {
    FILES, EDITOR, TERMINAL, GIT;

    companion object {
        /** The pane [stages] amounts to under the one-stage-at-a-time compact rule. */
        fun of(stages: StageVisibility, explorerSelected: Boolean): CompactPane = when {
            stages.bottom -> TERMINAL
            stages.left -> if (explorerSelected) FILES else GIT
            else -> EDITOR
        }
    }
}
