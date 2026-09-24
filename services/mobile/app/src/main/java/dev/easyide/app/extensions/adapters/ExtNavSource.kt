package dev.easyide.app.extensions.adapters

import dev.easyide.app.ui.shell.ext.ExtBadge
import dev.easyide.app.ui.shell.ext.ExtShell
import dev.easyide.app.ui.shell.nav.NavBadgeValue
import dev.easyide.app.ui.shell.nav.NavContribution
import dev.easyide.app.ui.shell.nav.NavItemSource
import dev.easyide.extensions.contrib.BadgeKind
import dev.easyide.extensions.json.numberOrNull
import dev.easyide.extensions.view.ViewData
import dev.easyide.extensions.whenclause.ContextLookup
import dev.easyide.extensions.whenclause.WhenEvaluator
import dev.easyide.extensions.whenclause.WhenParseResult
import dev.easyide.extensions.whenclause.WhenParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The shell's [NavItemSource] over the extensions' navigation items: [contributions] holds the items whose `when`
 * clause holds now (the clause is spent, so the shell needs none), and [badges] the number or dot each shows, read
 * from the data of the view it names ([viewData]). Both are recomputed only when something they read changes.
 */
class ExtNavSource(
    shell: StateFlow<ExtShell>,
    private val context: StateFlow<ContextLookup>,
    viewData: StateFlow<Map<String, JsonObject>>,
    scope: CoroutineScope,
) : NavItemSource {
    private val shown: StateFlow<ExtShell> = shell

    override val contributions: StateFlow<List<NavContribution>> = combine(shell, context) { ext, ctx ->
        ext.navigation.filter { c -> c.item.condition?.let { holds(it, ctx) } != false }.map { c -> c.copy(item = c.item.copy(condition = null)) }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val badges: StateFlow<Map<String, NavBadgeValue>> = combine(shell, viewData) { ext, data -> Badges.values(ext.badges, data) }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override val commands: Set<String> get() = shown.value.commands

    override fun holds(condition: String): Boolean = holds(condition, context.value)

    private fun holds(condition: String, ctx: ContextLookup): Boolean = when (val p = WhenParser.parse(condition)) {
        is WhenParseResult.Ok -> WhenEvaluator.evaluate(p.expr, ctx)
        is WhenParseResult.Error -> false
    }
}

/** What a badge shows: a count from a number at the bound path, a dot from any truthy value; nothing when the value is absent, zero or false. */
object Badges {

    fun values(badges: List<ExtBadge>, data: Map<String, JsonObject>): Map<String, NavBadgeValue> =
        badges.mapNotNull { b -> value(b, data[b.view])?.let { b.navId to it } }.toMap()

    private fun value(b: ExtBadge, data: JsonObject?): NavBadgeValue? {
        val v: JsonElement = data?.let { ViewData.get(it, b.path) } ?: return null
        return when (b.kind) {
            BadgeKind.COUNT -> (v as? JsonPrimitive)?.numberOrNull?.toInt()?.takeIf { it > 0 }?.let(NavBadgeValue::Count)
            BadgeKind.DOT -> NavBadgeValue.Dot.takeIf { WhenEvaluator.truthy(v) }
        }
    }
}
