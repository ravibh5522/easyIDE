package dev.easyide.app.extensions.adapters

import dev.easyide.extensions.contrib.ContributionOverrides
import dev.easyide.extensions.contrib.ContributionRef
import dev.easyide.extensions.contrib.ContributionSnapshot
import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.extensions.contrib.KeyRowContribution
import dev.easyide.extensions.contrib.MenuIds
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.contrib.RowKey
import dev.easyide.extensions.whenclause.ContextKeys
import dev.easyide.extensions.whenclause.ContextLookup

/** Where a key row is shown; key actions mean slightly different things on each (customization.md sec 10). */
enum class KeySurface { TERMINAL, EDITOR }

/** The row a surface shows, with `keyRow` menu commands appended; [owner] null = the user's `keyRows.layouts`. */
data class ActiveKeyRow(val id: String, val title: String, val owner: Owner?, val keys: List<RowKey>)

/** One row that `keyRows.active` can name: what the picker lists. */
data class KeyRowChoice(val id: String, val title: String, val owner: Owner?, val hidden: Boolean)

/**
 * Picks the key row for a surface from the user's `keyRows.layouts`, contributed rows and
 * the built-in `builtin.terminal` (EXT-31, customization.md sec 10).
 *
 * A user layout whose id equals an existing row **replaces** it (copy, then edit). The
 * candidates are user layouts, then contributed rows in enabled-set order, then built-ins,
 * reordered by `workbench.contributions.order["keyRows"]` and without hidden `keyRow:<id>`.
 *
 * `keyRows.active` names a row explicitly (both surfaces), unless it is missing or
 * hidden. `auto` picks the first candidate whose `when` holds for the surface:
 * - terminal: only rows whose `when` mentions `terminalFocus` are candidates, so a
 *   language row written for the editor never replaces the shell's arrows and `^C`;
 *   the fallback is `builtin.terminal`;
 * - editor: any row other than `builtin.terminal`; no row at all when none matches.
 */
object KeyRows {
    const val BUILTIN_TERMINAL = "builtin.terminal"

    private class Row(val value: KeyRowContribution, val owner: Owner?) {
        val id: String get() = value.id
    }

    fun active(
        surface: KeySurface,
        snapshot: ContributionSnapshot,
        activeSetting: String,
        auto: String,
        context: ContextLookup,
        hidden: Set<String>,
        userRows: List<KeyRowContribution> = emptyList(),
        order: Map<String, List<String>> = emptyMap(),
    ): ActiveKeyRow? {
        val rows = candidates(snapshot, userRows, order).filter { ref(it.id) !in hidden }
        val chosen = if (activeSetting != auto) {
            rows.firstOrNull { it.id == activeSetting } ?: autoPick(surface, rows, context)
        } else {
            autoPick(surface, rows, context)
        } ?: return null
        return ActiveKeyRow(chosen.id, chosen.value.title, chosen.owner, chosen.value.keys + menuKeys(snapshot, context, hidden, order))
    }

    /** Every row in candidate order, hidden ones flagged: the `keyRows.active` picker and the order editor. */
    fun available(
        snapshot: ContributionSnapshot,
        userRows: List<KeyRowContribution>,
        hidden: Set<String>,
        order: Map<String, List<String>>,
    ): List<KeyRowChoice> = candidates(snapshot, userRows, order).map { KeyRowChoice(it.id, it.value.title, it.owner, ref(it.id) in hidden) }

    private fun candidates(snapshot: ContributionSnapshot, userRows: List<KeyRowContribution>, order: Map<String, List<String>>): List<Row> {
        val user = userRows.distinctBy { it.id }.map { Row(it, null) }
        val replaced = user.mapTo(HashSet()) { it.id }
        val (builtIn, contributed) = snapshot.keyRows.filter { it.value.id !in replaced }.partition { it.owner == Owner.BuiltIn }
        val all = user + contributed.map { Row(it.value, it.owner) } + builtIn.map { Row(it.value, it.owner) }
        return ContributionOverrides(emptySet(), order).reorder(ContributionOverrides.KEY_ROWS, all) { it.id }
    }

    private fun autoPick(surface: KeySurface, rows: List<Row>, context: ContextLookup): Row? {
        val candidates = rows.filter { it.id != BUILTIN_TERMINAL }
        return when (surface) {
            KeySurface.TERMINAL -> candidates.firstOrNull { r ->
                r.value.`when`?.keys?.contains(ContextKeys.terminalFocus.name) == true && MenuModel.holds(r.value.`when`, context)
            } ?: rows.firstOrNull { it.id == BUILTIN_TERMINAL }
            KeySurface.EDITOR -> candidates.firstOrNull { MenuModel.holds(it.value.`when`, context) }
        }
    }

    /** Commands contributed to the `keyRow` menu, appended to whatever row is active. */
    private fun menuKeys(snapshot: ContributionSnapshot, context: ContextLookup, hidden: Set<String>, order: Map<String, List<String>>): List<RowKey> =
        MenuModel.items(MenuIds.KEY_ROW, snapshot, context, hidden, order = order)
            .filter { it.enabled }
            .map { RowKey(it.command.shortTitle ?: it.command.title, KeyAction.Command(it.command.command), null) }

    /** Ref of a row for `workbench.contributions.hidden` (`keyRow:<id>`). */
    fun ref(id: String): String = ContributionRef(ContributionRef.Kind.KEY_ROW, null, id).toString()
}
