package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.ui.graphics.Color
import dev.easyide.app.ui.theme.GitColors
import dev.easyide.sandbox.git.GitChangeType

/** The status letter a change row wears (VS Code's: A, M, D, U, C). */
internal val GitChangeType.letter: String
    get() = when (this) {
        GitChangeType.ADDED -> "A"
        GitChangeType.MODIFIED -> "M"
        GitChangeType.DELETED -> "D"
        GitChangeType.UNTRACKED -> "U"
        GitChangeType.CONFLICTED -> "C"
    }

internal fun GitChangeType.tint(git: GitColors): Color = when (this) {
    GitChangeType.ADDED -> git.added
    GitChangeType.MODIFIED -> git.modified
    GitChangeType.DELETED -> git.deleted
    GitChangeType.CONFLICTED -> git.conflict
    GitChangeType.UNTRACKED -> git.untracked
}
