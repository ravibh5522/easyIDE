package dev.easyide.app.extensions.adapters

import dev.easyide.extensions.contrib.ContributionRef
import dev.easyide.extensions.contrib.ContributionSnapshot
import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.extensions.contrib.KeyRowContribution
import dev.easyide.extensions.contrib.MenuIds
import dev.easyide.extensions.contrib.Owned
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.contrib.RowKey
import dev.easyide.extensions.whenclause.ContextKeys
import dev.easyide.extensions.whenclause.ContextLookup

/** Where a key row is shown; key actions mean slightly different things on each (customization.md sec 10). */
enum class KeySurface { TERMINAL, EDITOR }

/** The row a surface shows, with `keyRow` menu commands appended. */
data class ActiveKeyRow(val id: String, val title: String, val owner: Owner, val keys: List<RowKey>)

/**
 * Picks the key row for a surface from built-in and contributed rows (EXT-31).
 *
 * `keyRows.active` names a row explicitly (both surfaces), unless it is missing or
 * hidden. `auto` picks, in enabled-set order, the first contributed row whose `when`
 * holds for the surface:
 * - terminal: only rows whose `when` mentions `terminalFocus` are candidates, so a
 *   language row written for the editor never replaces the shell's arrows and `^C`;
 *   the fallback is `builtin.terminal`;
 * - editor: any row other than `builtin.terminal`; no row at all when none matches.
 */
object KeyRows {
    const val BUILTIN_TERMINAL = "builtin.terminal"

    fun active(
        surface: KeySurface,
        snapshot: ContributionSnapshot,
        activeSetting: String,
        auto: String,
        context: ContextLookup,
        hidden: Set<String>,
    ): ActiveKeyRow? {
        val rows = snapshot.keyRows.filter { it.ref.toString() !in hidden }
        val chosen = if (activeSetting != auto) {
            rows.firstOrNull { it.value.id == activeSetting } ?: autoPick(surface, rows, context)
        } else {
            autoPick(surface, rows, context)
        } ?: return null
        return ActiveKeyRow(chosen.value.id, chosen.value.title, chosen.owner, chosen.value.keys + menuKeys(snapshot, context, hidden))
    }

    private fun autoPick(surface: KeySurface, rows: List<Owned<KeyRowContribution>>, context: ContextLookup): Owned<KeyRowContribution>? {
        val contributed = rows.filter { it.value.id != BUILTIN_TERMINAL }
        return when (surface) {
            KeySurface.TERMINAL -> contributed.firstOrNull { r ->
                r.value.`when`?.keys?.contains(ContextKeys.terminalFocus.name) == true && MenuModel.holds(r.value.`when`, context)
            } ?: rows.firstOrNull { it.value.id == BUILTIN_TERMINAL }
            KeySurface.EDITOR -> contributed.firstOrNull { MenuModel.holds(it.value.`when`, context) }
        }
    }

    /** Commands contributed to the `keyRow` menu, appended to whatever row is active. */
    private fun menuKeys(snapshot: ContributionSnapshot, context: ContextLookup, hidden: Set<String>): List<RowKey> =
        MenuModel.items(MenuIds.KEY_ROW, snapshot, context, hidden)
            .filter { it.enabled }
            .map { RowKey(it.command.shortTitle ?: it.command.title, KeyAction.Command(it.command.command), null) }

    /** Ref of a row for `workbench.contributions.hidden` (`keyRow:<id>`). */
    fun ref(id: String): String = ContributionRef(ContributionRef.Kind.KEY_ROW, null, id).toString()
}
