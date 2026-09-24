package dev.easyide.app.extensions.adapters

import dev.easyide.extensions.action.EditorState
import dev.easyide.extensions.action.Template
import dev.easyide.extensions.action.WorkspaceState
import dev.easyide.extensions.contrib.ContributionOverrides
import dev.easyide.extensions.contrib.ContributionSnapshot
import dev.easyide.extensions.contrib.Owned
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.contrib.StatusBarAlignment
import dev.easyide.extensions.contrib.StatusBarItemContribution
import dev.easyide.extensions.whenclause.ContextLookup
import kotlinx.serialization.json.JsonElement

/** One rendered status bar item; [command] runs on tap. */
data class StatusItem(val id: String, val text: String, val tooltip: String?, val command: String?, val alignment: StatusBarAlignment, val owner: Owner)

/**
 * `easyide.statusBarItems` -> the status bar (EXT-29): items whose `when` holds and that
 * are not hidden (`statusBar:<id>`), left then right, higher `priority` first (VS Code),
 * text rendered with [SyncVariables] (it is re-rendered on every recomposition, so only
 * side-effect-free variables apply). Items whose text renders empty are not shown.
 */
object StatusItems {

    fun items(
        snapshot: ContributionSnapshot,
        context: ContextLookup,
        hidden: Set<String>,
        config: (String) -> JsonElement?,
        editor: EditorState?,
        workspace: WorkspaceState,
        order: Map<String, List<String>> = emptyMap(),
    ): List<StatusItem> = ordered(snapshot.statusBarItems.filter { it.ref.toString() !in hidden && MenuModel.holds(it.value.`when`, context) }, order)
        .map { o ->
            val v = o.value
            val render = { t: Template -> SyncVariables.render(t, config, editor, workspace, o.owner) }
            StatusItem(v.id, render(v.text), v.tooltip?.let(render), v.command, v.alignment, o.owner)
        }
        .filter { it.text.isNotBlank() }

    /**
     * Ids of every non-hidden item at [alignment] in display order, ignoring `when`: the
     * list a "move left/right" edits (location `statusBar.left` / `statusBar.right`).
     */
    fun orderedIds(snapshot: ContributionSnapshot, alignment: StatusBarAlignment, hidden: Set<String>, order: Map<String, List<String>>): List<String> =
        ordered(snapshot.statusBarItems.filter { it.value.alignment == alignment && it.ref.toString() !in hidden }, order).map { it.value.id }

    /** Left then right; per side higher `priority` first, then `workbench.contributions.order`. */
    private fun ordered(items: List<Owned<StatusBarItemContribution>>, order: Map<String, List<String>>): List<Owned<StatusBarItemContribution>> {
        val overrides = ContributionOverrides(emptySet(), order)
        return StatusBarAlignment.entries.flatMap { side ->
            val sorted = items.filter { it.value.alignment == side }.sortedByDescending { it.value.priority }
            overrides.reorder(ContributionOverrides.statusBarLocation(side), sorted) { it.value.id }
        }
    }
}
