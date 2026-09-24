package dev.easyide.app.ui.screens.workspace.ext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.WorkbenchSettingsSchema
import dev.easyide.app.extensions.adapters.UserKeyRows
import dev.easyide.extensions.contrib.ContributionOverrides
import dev.easyide.extensions.contrib.KeyRowContribution
import dev.easyide.app.extensions.ExtensionsContainer
import dev.easyide.app.extensions.adapters.ActiveKeyRow
import dev.easyide.app.extensions.adapters.KeyRows
import dev.easyide.app.extensions.adapters.KeySurface
import dev.easyide.app.extensions.adapters.MenuEntry
import dev.easyide.app.extensions.adapters.MenuModel
import dev.easyide.app.extensions.adapters.StatusItem
import dev.easyide.app.extensions.adapters.StatusItems
import dev.easyide.app.ui.commands.Command
import dev.easyide.app.ui.commands.CommandRegistry
import dev.easyide.app.ui.commands.CommandTitle
import dev.easyide.app.ui.commands.KeyBinding
import dev.easyide.app.ui.foundation.LocalSettings
import dev.easyide.extensions.contrib.ContributionSnapshot
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.settings.SettingsQuery
import dev.easyide.extensions.whenclause.ContextKeys
import dev.easyide.extensions.whenclause.ContextLookup
import dev.easyide.extensions.whenclause.ContextSnapshot
import dev.easyide.sandbox.files.FileNode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The contribution adapters of one workspace screen, evaluated per recomposition over
 * the live registry snapshot and context-key snapshot (both StateFlows, so enabling a
 * pack or moving the caret recomposes exactly what depends on it). Pure projections:
 * [MenuModel], [StatusItems], [KeyRows].
 */
class WorkspaceContributions(
    val snapshot: ContributionSnapshot,
    val context: ContextSnapshot,
    val hidden: Set<String>,
    val keyRowSetting: String,
    /** `workbench.contributions.order`, by location. */
    val order: Map<String, List<String>>,
    /** The user's `keyRows.layouts` rows (bad entries already dropped). */
    val userKeyRows: List<KeyRowContribution>,
    val keybindings: List<KeyBinding>,
    private val host: WorkspaceExtensionHost,
    private val extensions: ExtensionsContainer,
) {
    /** Extension commands for the palette and keymap (built-ins keep their own entries). */
    fun commands(): List<Command> = snapshot.commands
        .filter { it.owner is Owner.Ext && MenuModel.inPalette(it.value.command, snapshot, context, hidden) }
        .map { owned ->
            val c = owned.value
            Command(
                id = c.command,
                title = CommandTitle.Text(c.title),
                category = c.category,
                enabled = MenuModel.holds(c.enablement, context),
                runWithArgs = { args -> host.run(c.command, args) },
                run = { host.run(c.command) },
            )
        }

    /**
     * Entries of [menuId]. [builtIns] is the screen's command list of this composition: an
     * entry bound to a built-in command is greyed while that command is disabled.
     */
    fun menu(menuId: String, builtIns: CommandRegistry? = null, scoped: ContextLookup = context): List<MenuEntry> =
        MenuModel.items(menuId, snapshot, scoped, hidden, { id -> builtIns?.get(id)?.enabled ?: true }, order)

    /** Explorer items see the node as the focused resource (`resource*` keys), VS Code's scoped context. */
    fun explorerMenu(node: FileNode): List<MenuEntry> {
        val path = GUEST + "/" + node.relativePath
        val lookup = context.with(mapOf(
            ContextKeys.resourcePath.name to JsonPrimitive(path),
            ContextKeys.resourceFilename.name to JsonPrimitive(node.name),
            ContextKeys.resourceDirname.name to JsonPrimitive(path.substringBeforeLast('/')),
            ContextKeys.resourceExtname.name to JsonPrimitive(dev.easyide.extensions.action.GuestPaths.extname(path)),
            ContextKeys.resourceIsFolder.name to JsonPrimitive(node.isDirectory),
            ContextKeys.explorerFocus.name to JsonPrimitive(true),
        ))
        return menu(dev.easyide.extensions.contrib.MenuIds.EXPLORER_CONTEXT, scoped = lookup)
    }

    fun statusItems(): List<StatusItem> {
        val editor = host.editorState()
        val query = SettingsQuery(editor?.languageId, host.environmentId, null)
        val contributed = StatusItems.items(snapshot, context, hidden, { key -> extensions.settings.value(key, query) }, editor, host.workspaceState(), order)
        // Items WASM extensions set at runtime follow the contributed ones on their side.
        return (contributed + extensions.wasm.ui.ordered()).sortedBy { it.alignment.ordinal }
    }

    fun keyRow(surface: KeySurface): ActiveKeyRow? {
        val scoped = when (surface) {
            KeySurface.TERMINAL -> context.with(mapOf(ContextKeys.terminalFocus.name to JsonPrimitive(true)))
            KeySurface.EDITOR -> context.with(mapOf(ContextKeys.terminalFocus.name to JsonPrimitive(false)))
        }
        return KeyRows.active(surface, snapshot, keyRowSetting, SettingsSchema.KEY_ROWS_AUTO, scoped, hidden, userKeyRows, order)
    }

    fun run(entry: MenuEntry, args: JsonElement? = null) = host.run(entry.command.command, args)

    /** VS Code passes the resource URI as the first argument of an explorer command. */
    fun runOnNode(entry: MenuEntry, node: FileNode) = run(entry, JsonPrimitive(FILE_URI + GUEST + "/" + node.relativePath))

    private companion object {
        const val GUEST = "/workspace"
        const val FILE_URI = "file://"
    }
}

@Composable
fun rememberWorkspaceContributions(host: WorkspaceExtensionHost, extensions: ExtensionsContainer): WorkspaceContributions {
    val snapshot by extensions.runtime.contributions.snapshot.collectAsStateWithLifecycle()
    val context by extensions.runtime.contextKeys.snapshot.collectAsStateWithLifecycle()
    val keybindings by extensions.keybindings.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val hidden = settings[SettingsSchema.contributionsHidden]
    val order = settings[WorkbenchSettingsSchema.contributionsOrder]
    val layouts = settings[WorkbenchSettingsSchema.keyRowLayouts]
    val keyRow = settings[SettingsSchema.keyRowsActive]
    // `config.*` when-clauses and `${config:}` status texts resolve lazily, so a settings
    // change (a toggle command) must rebuild the projections even when no context key moved.
    val settingsVersion by extensions.settings.version.collectAsStateWithLifecycle()
    val wasmStatus by extensions.wasm.ui.statusItems.collectAsStateWithLifecycle()
    return remember(snapshot, context, keybindings, hidden, order, layouts, keyRow, settingsVersion, wasmStatus) {
        // NON_HIDEABLE refs are dropped here, so no settings file can hide the way back.
        val overrides = ContributionOverrides.of(hidden, ContributionOverrides.parseOrder(order))
        WorkspaceContributions(
            snapshot, context, overrides.hidden, keyRow, overrides.order, UserKeyRows.decode(layouts).rows, keybindings, host, extensions,
        )
    }
}
