package dev.easyide.app.extensions

import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.extensions.ExtensionPolicy
import dev.easyide.extensions.action.Action
import dev.easyide.extensions.action.JsonTemplate
import dev.easyide.extensions.action.QuickPickSource
import dev.easyide.extensions.action.VariableRef
import dev.easyide.extensions.capability.Capability
import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.whenclause.ContextKeys
import dev.easyide.extensions.whenclause.WhenParseResult
import dev.easyide.extensions.whenclause.WhenParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static integrity of every built-in pack against the app: each command a menu, keybinding,
 * status item, key row or `executeCommand` names resolves in the registry the app builds;
 * each when-clause parses and names only known context keys and declared settings; each
 * setting an action writes exists, accepts every value written and is writable by that pack.
 */
class PackReferencesTest {

    private val ids = BuiltInPackFixtures.ids()
    private val descriptors: List<ExtensionDescriptor> = ids.map { BuiltInPackFixtures.load(it).descriptor }
    private val builtInKeys = SettingsSchema.all.associateBy { it.key }
    private val contributedKeys = descriptors.flatMap { d -> d.contributes.configuration.map { it.key } }.toSet()

    @Test fun `the built-in command table covers exactly the app's command ids`() {
        val table = BuiltInCommandTable.ALL.map { it.id }
        assertEquals("duplicates", table.size, table.toSet().size)
        assertEquals(CommandIds.ALL, table.toSet())
    }

    @Test fun `the registry over all packs has no conflicts`() {
        assertEquals(emptyList<Any>(), BuiltInPackFixtures.snapshot().conflicts)
    }

    @Test fun `every referenced command resolves in the registry`() {
        val registered = BuiltInPackFixtures.snapshot().commands.map { it.value.command }.toSet()
        for (d in descriptors) {
            val c = d.contributes
            val refs = c.menus.flatMap { listOfNotNull(it.command, it.alt) } +
                c.keybindings.map { it.command } +
                c.statusBarItems.mapNotNull { it.command } +
                c.keyRows.flatMap { r -> r.keys.flatMap { listOfNotNull(it.action, it.longPress) } }
                    .filterIsInstance<KeyAction.Command>().map { it.id } +
                d.actions.values.flatMap(::executeTargets)
            refs.forEach { assertTrue("${d.id}: '$it' is not a registered command", it in registered) }
            assertEquals("${d.id}: every command has an action", c.commands.map { it.command }.toSet(), d.actions.keys)
        }
    }

    @Test fun `every when-clause parses and names known keys and declared settings`() {
        for (id in ids) {
            val clauses = whenStrings(BuiltInPackFixtures.manifest(id))
            for (text in clauses) {
                val parsed = WhenParser.parse(text)
                assertTrue("$id: '$text' does not parse: $parsed", parsed is WhenParseResult.Ok)
                for (key in (parsed as WhenParseResult.Ok).expr.keys) {
                    assertTrue("$id: '$text' uses unknown key '$key'", ContextKeys.isKnown(key))
                    if (key.startsWith(ContextKeys.CONFIG_PREFIX)) {
                        val setting = key.removePrefix(ContextKeys.CONFIG_PREFIX)
                        assertTrue("$id: '$text' reads undeclared setting '$setting'", setting in builtInKeys || setting in contributedKeys)
                    }
                }
            }
        }
    }

    @Test fun `every setting an action writes exists, accepts the value and is writable by its pack`() {
        for (d in descriptors) {
            val own = d.contributes.configuration.map { it.key }.toSet()
            val mayWriteForeign = d.capabilities.satisfies(Capability.UiSettings)
            for ((command, action) in d.actions) {
                for ((key, values) in configWrites(action)) {
                    val where = "${d.id} $command '$key'"
                    assertTrue("$where is protected", !ExtensionPolicy.isUnwritable(key))
                    assertTrue("$where needs ui.settings", key in own || mayWriteForeign)
                    val setting = builtInKeys[key]
                    assertTrue("$where is not a declared setting", setting != null || key in own)
                    values.forEach { v -> assertTrue("$where rejects $v", setting == null || setting.isValid(v)) }
                }
            }
        }
    }

    @Test fun `the key row chooser offers exactly auto and the pack's editor rows`() {
        val d = descriptors.single { it.id.value == "easyide.key-rows" }
        val pick = (d.actions.getValue("key-rows.chooseLayout") as Action.Sequence).steps.filterIsInstance<Action.ShowQuickPick>().single()
        val offered = pick.values()
        val editorRows = d.contributes.keyRows.filter { r -> r.`when`?.keys?.contains(ContextKeys.terminalFocus.name) != true }.map { it.id }
        assertEquals(listOf(SettingsSchema.KEY_ROWS_AUTO) + editorRows, offered)
    }

    private fun executeTargets(a: Action): List<String> = when (a) {
        is Action.ExecuteCommand -> listOf(a.command)
        is Action.Sequence -> a.steps.flatMap(::executeTargets)
        is Action.ShowMessage -> a.actions.mapNotNull { it.action }.flatMap(::executeTargets)
        else -> emptyList()
    }

    /**
     * Settings written by [a] and the values it can write: a toggle's cycle, a literal
     * `setConfig` value, or for `setConfig` of `${input:x}` the values of the quick pick `x`.
     */
    private fun configWrites(a: Action, picks: Map<String, List<JsonElement>> = emptyMap()): List<Pair<String, List<JsonElement>>> = when (a) {
        is Action.ToggleConfig -> listOf(a.key to a.values)
        is Action.SetConfig -> {
            val value = a.value
            val written = when {
                value is JsonTemplate.Leaf -> listOf(value.value)
                value is JsonTemplate.Text && value.template.singleVariable is VariableRef.Input ->
                    picks.getValue((value.template.singleVariable as VariableRef.Input).id)
                else -> error("setConfig of ${a.key}: only literal or \${input:} values are checked")
            }
            listOf(a.key to written)
        }
        is Action.Sequence -> {
            val bound = a.steps.filterIsInstance<Action.ShowQuickPick>().filter { it.items is QuickPickSource.Items }
                .associate { it.id to it.values().map(::JsonPrimitive) }
            a.steps.flatMap { configWrites(it, picks + bound) }
        }
        is Action.ShowMessage -> a.actions.mapNotNull { it.action }.flatMap { configWrites(it, picks) }
        else -> emptyList()
    }

    private fun Action.ShowQuickPick.values(): List<String> =
        (items as QuickPickSource.Items).items.map { (it.value as JsonTemplate.Leaf).value.stringOrNull!! }

    /** Every `when` and `enablement` string in a manifest. */
    private fun whenStrings(e: JsonElement): List<String> = when (e) {
        is JsonObject -> e.flatMap { (k, v) ->
            if ((k == "when" || k == "enablement") && v is JsonPrimitive) listOf(v.content) else whenStrings(v)
        }
        is JsonArray -> e.flatMap(::whenStrings)
        else -> emptyList()
    }
}
