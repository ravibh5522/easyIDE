package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.theme.GitColors
import dev.easyide.sandbox.git.GitChangeType
import dev.easyide.sandbox.git.GitStatus

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

/** The letter of a change at the end of a row, tinted by its kind; the tree and the change list wear the same one. */
@Composable
internal fun GitStatusLetter(type: GitChangeType) {
    BasicText(type.letter, style = Kit.text.monoSmall.copy(color = type.tint(Kit.colors.git)), maxLines = 1)
}

/** The kind of change per project path, for the explorer: a file changed in the working tree shows that over its staged kind. */
internal fun GitStatus.changeByPath(): Map<String, GitChangeType> =
    (staged + unstaged + conflicting).associate { it.path to it.type }
