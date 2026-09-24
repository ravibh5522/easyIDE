package dev.easyide.app.extensions

import dev.easyide.app.extensions.adapters.EditorKeys
import dev.easyide.app.extensions.adapters.KeyRows
import dev.easyide.app.extensions.adapters.KeySurface
import dev.easyide.app.extensions.adapters.MenuModel
import dev.easyide.app.extensions.adapters.StatusItems
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.app.ui.commands.KeyNames
import dev.easyide.app.ui.commands.Keymap
import dev.easyide.extensions.action.Action
import dev.easyide.extensions.action.Template
import dev.easyide.extensions.action.WorkspaceState
import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.extensions.contrib.MenuIds
import dev.easyide.extensions.contrib.RowKey
import dev.easyide.extensions.whenclause.ContextKeys
import dev.easyide.extensions.whenclause.ContextLookup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The built-in packs as the workspace screen renders them: the touch toolbar and title
 * menus per editor state, the key row per surface and setting, the status items per
 * setting value, and every shell command line they send is valid `sh` syntax.
 */
class PackBehaviourTest {

    private val snapshot = BuiltInPackFixtures.snapshot()
    private val descriptors = BuiltInPackFixtures.ids().map { BuiltInPackFixtures.load(it).descriptor }

    private fun context(vararg pairs: Pair<String, String>): ContextLookup {
        val map: Map<String, JsonElement> = pairs.associate { (k, v) -> k to Json.parseToJsonElement(v) }
        return ContextLookup { map[it] }
    }

    private val pythonEditor = arrayOf(
        ContextKeys.editorLangId.name to "\"python\"", ContextKeys.editorReadonly.name to "false",
        ContextKeys.resourceFilename.name to "\"app.py\"", ContextKeys.resourcePath.name to "\"/workspace/app.py\"",
    )

    private fun toolbar(ctx: ContextLookup, builtInEnabled: (String) -> Boolean = { true }) =
        MenuModel.items(MenuIds.EDITOR_TOUCH_TOOLBAR, snapshot, ctx, emptySet(), builtInEnabled)

    @Test fun `the touch toolbar shows the editing actions of a writable, dirty editor in group order`() {
        val ctx = context(*pythonEditor, ContextKeys.activeEditorIsDirty.name to "true", ContextKeys.editorHasSelection.name to "false")
        assertEquals(
            listOf(
                CommandIds.SAVE, CommandIds.TOGGLE_LINE_COMMENT, CommandIds.FORMAT_DOCUMENT, CommandIds.QUICK_FIX, CommandIds.RENAME,
                CommandIds.REVEAL_DEFINITION, CommandIds.GO_TO_REFERENCES, CommandIds.SHOW_HOVER, CommandIds.GOTO_SYMBOL, CommandIds.SHOW_COMMANDS,
                // easyide.python's entry has no group, so it sorts after every grouped one.
                PYTHON_RUN,
            ),
            toolbar(ctx).map { it.command.command },
        )
    }

    @Test fun `a selection swaps format document for format selection, a read-only editor drops the edits`() {
        val selected = toolbar(context(*pythonEditor, ContextKeys.editorHasSelection.name to "true")).map { it.command.command }
        assertTrue(CommandIds.FORMAT_SELECTION in selected && CommandIds.FORMAT_DOCUMENT !in selected)
        val readonly = toolbar(context(*pythonEditor, ContextKeys.editorReadonly.name to "true")).map { it.command.command }
        assertEquals(
            listOf(CommandIds.REVEAL_DEFINITION, CommandIds.GO_TO_REFERENCES, CommandIds.SHOW_HOVER, CommandIds.GOTO_SYMBOL, CommandIds.SHOW_COMMANDS, PYTHON_RUN),
            readonly,
        )
    }

    @Test fun `a built-in entry is greyed exactly while the app command is disabled`() {
        val lspDown = setOf(CommandIds.FORMAT_DOCUMENT, CommandIds.RENAME, CommandIds.QUICK_FIX)
        val entries = toolbar(context(*pythonEditor)) { it !in lspDown }.filter { it.command.command in CommandIds.ALL }
        entries.forEach { assertEquals(it.command.command, it.command.command !in lspDown, it.enabled) }
    }

    @Test fun `builtInEnabled never greys an extension command`() {
        val ctx = context(*pythonEditor, ContextKeys.resourceFilename.name to "\"test_app.py\"", ContextKeys.envState.name to "\"ready\"")
        val test = toolbar(ctx) { false }.single { it.command.command == "project-tasks.pytestAtCursor" }
        assertTrue(test.enabled)
        assertFalse(toolbar(ctx) { false }.single { it.command.command == CommandIds.SHOW_COMMANDS }.enabled)
    }

    @Test fun `task buttons follow the open file`() {
        fun title(file: String, lang: String? = null) = MenuModel.items(
            MenuIds.EDITOR_TITLE, snapshot,
            context(*listOfNotNull(ContextKeys.resourceFilename.name to "\"$file\"", lang?.let { ContextKeys.editorLangId.name to "\"$it\"" }).toTypedArray()),
            emptySet(),
        ).filter { it.group == MenuIds.NAVIGATION_GROUP }.map { it.command.command }
        assertEquals(listOf("project-tasks.makeTarget"), title("Makefile"))
        assertEquals(listOf("project-tasks.npmScript"), title("package.json", "json"))
        assertEquals(listOf("project-tasks.cargoBuild", "project-tasks.cargoRun"), title("Cargo.toml"))
        assertEquals(listOf(PYTHON_RUN, "project-tasks.pytestFile"), title("test_app.py", "python"))
        assertEquals(listOf(PYTHON_RUN), title("app.py", "python"))
    }

    @Test fun `key rows - explicit choice on both surfaces, shell row only in the terminal and only when turned on`() {
        val editor = context(*pythonEditor, ContextKeys.terminalFocus.name to "false")
        val terminal = context(ContextKeys.terminalFocus.name to "true")
        fun row(surface: KeySurface, setting: String, ctx: ContextLookup) = KeyRows.active(surface, snapshot, setting, AUTO, ctx, emptySet())?.id
        assertEquals("key-rows.symbols", row(KeySurface.EDITOR, "key-rows.symbols", editor))
        assertEquals("key-rows.navigation", row(KeySurface.TERMINAL, "key-rows.navigation", terminal))
        assertEquals("core-snippets.code", row(KeySurface.EDITOR, AUTO, editor))
        assertEquals(KeyRows.BUILTIN_TERMINAL, row(KeySurface.TERMINAL, AUTO, terminal))
        val shellOn = ContextKeys.CONFIG_PREFIX + "key-rows.shellRowInTerminal" to "true"
        assertEquals("key-rows.shell", row(KeySurface.TERMINAL, AUTO, context(ContextKeys.terminalFocus.name to "true", shellOn)))
        assertEquals("core-snippets.code", row(KeySurface.EDITOR, AUTO, context(*pythonEditor, ContextKeys.terminalFocus.name to "false", shellOn)))
    }

    @Test fun `every key-row key names a real chord, and editor-row keys edit or move the caret`() {
        for (d in descriptors) for (r in d.contributes.keyRows) {
            val terminalRow = r.`when`?.keys?.contains(ContextKeys.terminalFocus.name) == true
            r.keys.flatMap(::actions).filterIsInstance<KeyAction.Key>().forEach { k ->
                val chord = KeyNames.parse(k.chord)
                assertNotNull("${r.id}: '${k.chord}'", chord)
                if (!terminalRow) assertNotNull("${r.id}: '${k.chord}' does nothing in the editor", EditorKeys.apply("ab\ncd", 1, 1, chord!!.keyCode))
            }
        }
    }

    @Test fun `ctrl+slash toggles the line comment outside the terminal`() {
        val chord = KeyNames.parse("ctrl+/")!!
        assertEquals(CommandIds.TOGGLE_LINE_COMMENT, Keymap.DEFAULT.bindingFor(chord, terminalFocused = false)?.command)
        assertEquals(null, Keymap.DEFAULT.bindingFor(chord, terminalFocused = true))
    }

    @Test fun `toggle status items show exactly one state per setting value`() {
        fun texts(vararg config: Pair<String, String>) = StatusItems.items(
            snapshot, context(*pythonEditor, *config.map { (k, v) -> ContextKeys.CONFIG_PREFIX + k to v }.toTypedArray()), emptySet(),
            { null }, null, WorkspaceState(WorkspaceState.GUEST_WORKSPACE, "p", null, null),
        ).filter { it.id.startsWith("toggles.") }.map { it.text }
        assertEquals(listOf("Format on save: off", "Inlay hints: on"), texts("editor.formatOnSave" to "false", "editor.inlayHints.enabled" to "\"ON\""))
        assertEquals(listOf("Format on save: on", "Inlay hints: off"), texts("editor.formatOnSave" to "true", "editor.inlayHints.enabled" to "\"OFF\""))
        assertEquals(listOf("Format on save: off", "Inlay hints: on"), texts("editor.inlayHints.enabled" to "\"ON_UNLESS_PRESSED\""))
    }

    @Test fun `every shell command line is valid sh`() {
        for (d in descriptors) for ((id, action) in d.actions) {
            for (script in shellScripts(action)) {
                val p = ProcessBuilder("sh", "-n", "-c", script).redirectErrorStream(true).start()
                assertTrue("$id: sh timed out", p.waitFor(SH_TIMEOUT_SEC, TimeUnit.SECONDS))
                assertEquals("$id: `$script`: ${p.inputStream.bufferedReader().readText()}", 0, p.exitValue())
            }
        }
    }

    private fun actions(k: RowKey) = listOfNotNull(k.action, k.longPress)

    /** `runInTerminal` lines (variables stand in as quoted words, as the runner quotes them) and `sh -c` scripts. */
    private fun shellScripts(a: Action): List<String> = when (a) {
        is Action.RunInTerminal -> listOf(sample(a.command))
        is Action.SandboxExec -> {
            val argv = a.command.map { it.source }
            if (argv.size >= 3 && argv[0] == SH && argv[1] == SH_C) listOf(argv[2]) else emptyList()
        }
        is Action.Sequence -> a.steps.flatMap(::shellScripts)
        is Action.ShowMessage -> a.actions.mapNotNull { it.action }.flatMap(::shellScripts)
        else -> emptyList()
    }

    private fun sample(t: Template): String = t.segments.joinToString("") { s ->
        when (s) {
            is Template.Segment.Literal -> s.text
            is Template.Segment.Var -> "'x'"
        }
    }

    private companion object {
        const val AUTO = "auto"
        const val SH = "sh"
        const val SH_C = "-c"
        const val SH_TIMEOUT_SEC = 10L
    }
}

private const val PYTHON_RUN = "python.runFile"
