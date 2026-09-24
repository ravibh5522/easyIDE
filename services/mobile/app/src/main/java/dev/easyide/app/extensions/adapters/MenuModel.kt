package dev.easyide.app.extensions.adapters

import dev.easyide.extensions.contrib.CommandContribution
import dev.easyide.extensions.contrib.ContributionRef
import dev.easyide.extensions.contrib.ContributionSnapshot
import dev.easyide.extensions.contrib.MenuIds
import dev.easyide.extensions.contrib.MenuItemContribution
import dev.easyide.extensions.contrib.Owned
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.whenclause.ContextLookup
import dev.easyide.extensions.whenclause.WhenEvaluator
import dev.easyide.extensions.whenclause.WhenExpr

/** One visible menu item: the winning command it runs and whether its `enablement` holds. */
data class MenuEntry(
    val ref: ContributionRef,
    val owner: Owner,
    val command: CommandContribution,
    val group: String?,
    val enabled: Boolean,
)

/**
 * Projects the registry's menu store into one native menu slot (extension-runtime.md
 * sec 7.2 `MenuModel`): entries of a menu id whose `when` holds on the context, not hidden
 * by `workbench.contributions.hidden`, bound to a command that exists, sorted the VS Code
 * way (group `navigation` first, then group name, then `@order`, then title; ungrouped
 * last). An entry whose command's `enablement` is false stays listed but disabled.
 *
 * Pure: evaluated per recomposition against the current snapshots.
 */
object MenuModel {

    private class Candidate(val item: Owned<MenuItemContribution>, val command: CommandContribution, val enabled: Boolean) {
        val group: String? get() = item.value.group
        val rank: Int get() = when (group) {
            MenuIds.NAVIGATION_GROUP -> RANK_NAVIGATION
            null -> RANK_UNGROUPED
            else -> RANK_GROUPED
        }
    }

    private val ORDER = compareBy<Candidate>(
        { it.rank },
        { it.group.orEmpty() },
        { it.item.value.order ?: Double.MAX_VALUE },
        { it.command.title },
    )

    /**
     * [builtInEnabled] is the live enabled state of an app command (its `CommandRegistry`
     * entry): built-ins carry no `enablement` clause, their availability (an editable tab,
     * a server offering the feature) is known only to the screen, so an entry bound to one
     * is greyed exactly when the palette greys the command.
     */
    fun items(
        menuId: String,
        snapshot: ContributionSnapshot,
        context: ContextLookup,
        hidden: Set<String>,
        builtInEnabled: (String) -> Boolean = { true },
    ): List<MenuEntry> {
        val commands = snapshot.commands.associateBy { it.value.command }
        return snapshot.menus
            .filter { it.value.menuId == menuId && it.ref.toString() !in hidden && holds(it.value.`when`, context) }
            .mapNotNull { m ->
                val owned = commands[m.value.command] ?: return@mapNotNull null
                val command = owned.value
                val live = owned.owner !is Owner.BuiltIn || builtInEnabled(command.command)
                Candidate(m, command, live && holds(command.enablement, context))
            }
            .sortedWith(ORDER)
            .map { MenuEntry(it.item.ref, it.item.owner, it.command, it.group, it.enabled) }
    }

    /**
     * Whether [commandId] belongs in the palette: VS Code lists every command unless a
     * `commandPalette` menu entry for it has a `when` that is false (or is hidden).
     */
    fun inPalette(commandId: String, snapshot: ContributionSnapshot, context: ContextLookup, hidden: Set<String>): Boolean {
        val entries = snapshot.menus.filter { it.value.menuId == MenuIds.COMMAND_PALETTE && it.value.command == commandId }
        if (entries.any { it.ref.toString() in hidden }) return false
        return entries.isEmpty() || entries.any { holds(it.value.`when`, context) }
    }

    fun holds(expr: WhenExpr?, context: ContextLookup): Boolean = expr == null || WhenEvaluator.evaluate(expr, context)

    private const val RANK_NAVIGATION = 0
    private const val RANK_GROUPED = 1
    private const val RANK_UNGROUPED = 2
}
