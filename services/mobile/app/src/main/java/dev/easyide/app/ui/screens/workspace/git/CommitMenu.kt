package dev.easyide.app.ui.screens.workspace.git

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.sandbox.git.GitRef
import dev.easyide.sandbox.git.GitRefKind

/** A question the commit menu asks before it acts: a name for a branch, a name and message for a tag, a ref to compare with. */
internal sealed interface HistoryAsk {
    val sha: String
    val label: String

    data class Branch(override val sha: String, override val label: String) : HistoryAsk
    data class Tag(override val sha: String, override val label: String) : HistoryAsk
    data class CompareWith(override val sha: String, override val label: String) : HistoryAsk
}

/** Everything the commit menu reads and calls, so building the menu is one function of these. */
internal class CommitMenuEnv(
    val git: GitControllers,
    val hasUpstream: Boolean,
    val busy: Boolean,
    val onOpenChanges: (String) -> Unit,
    val onAsk: (HistoryAsk) -> Unit,
)

/** The branches a commit carries that a checkout can go to or a delete can remove: not the checked-out one, and no tags. */
internal fun checkoutTargets(refs: List<GitRef>): List<GitRef> = refs.filter { it.kind != GitRefKind.TAG && !it.isCurrent }

internal fun deletableBranches(refs: List<GitRef>): List<GitRef> = refs.filter { it.kind == GitRefKind.LOCAL && !it.isCurrent }

/**
 * VS Code's commit context menu: open changes; checkout (its branches, or detached); branch, tag and
 * cherry-pick; the three comparisons; copy. Extension-provided entries (`Add to chat`, `Explain changes`) have
 * no contribution point in the app yet, so none are listed; docs/ui-redesign/vscode-parity-git.md lists the hook.
 */
@Composable
internal fun commitMenuItems(row: GraphRow, refs: List<GitRef>, env: CommitMenuEnv): List<KitMenuItem> {
    val commit = row.commit
    val context = LocalContext.current
    val history = env.git.history
    val idle = !env.busy
    val checkout = checkoutTargets(refs).map { ref -> KitMenuItem.Action(ref.name, { env.git.branches.switchTo(ref.name) }, enabled = idle) }
    val delete = deletableBranches(refs).map { ref -> KitMenuItem.Action(ref.name, { env.git.branches.requestDelete(ref.name) }, enabled = idle) }
    val cherryPickable = commit.parents.size <= 1 && !refs.any { it.isCurrent }
    return listOf(
        KitMenuItem.Action(stringResource(R.string.gitui_h_open_changes), { env.onOpenChanges(commit.id) }),
        KitMenuItem.Divider,
        KitMenuItem.Submenu(stringResource(R.string.gitui_h_checkout), checkout),
        KitMenuItem.Action(stringResource(R.string.gitui_h_checkout_detached), { history.checkoutDetached(commit.id) }, enabled = idle),
        KitMenuItem.Divider,
        KitMenuItem.Action(stringResource(R.string.gitui_h_create_branch), { env.onAsk(HistoryAsk.Branch(commit.id, commit.shortId)) }, enabled = idle),
        KitMenuItem.Submenu(stringResource(R.string.gitui_h_delete_branch), delete),
        KitMenuItem.Action(stringResource(R.string.gitui_h_create_tag), { env.onAsk(HistoryAsk.Tag(commit.id, commit.shortId)) }, enabled = idle),
        KitMenuItem.Divider,
        KitMenuItem.Action(stringResource(R.string.gitui_h_cherry_pick), { history.cherryPick(commit.id) }, enabled = idle && cherryPickable),
        KitMenuItem.Divider,
        KitMenuItem.Action(stringResource(R.string.gitui_h_compare_remote), { history.compareWithRemote(commit.id, commit.shortId) }, enabled = env.hasUpstream),
        KitMenuItem.Action(stringResource(R.string.gitui_h_compare_merge_base), { history.compareWithMergeBase(commit.id, commit.shortId) }),
        KitMenuItem.Action(stringResource(R.string.gitui_h_compare_with), { env.onAsk(HistoryAsk.CompareWith(commit.id, commit.shortId)) }),
        KitMenuItem.Divider,
        KitMenuItem.Action(stringResource(R.string.gitui_h_copy_hash), { copyText(context, commit.id) }),
        KitMenuItem.Action(stringResource(R.string.gitui_h_copy_message), { copyText(context, listOf(commit.subject, commit.body).filter { it.isNotBlank() }.joinToString("\n\n")) }),
    )
}

/** The system clipboard; called on a tap, on the main thread. */
internal fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, text))
}
