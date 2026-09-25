package dev.easyide.app.ui.screens.workspace.golden

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.easyide.app.R
import dev.easyide.app.ui.commands.Command
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.app.ui.commands.CommandRegistry
import dev.easyide.app.ui.commands.KeyBinding
import dev.easyide.app.ui.commands.KeyChord
import dev.easyide.app.ui.commands.KeyFocus
import dev.easyide.app.ui.commands.Keymap
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.screens.workspace.EditHistories
import dev.easyide.app.ui.screens.workspace.EditorSelections
import dev.easyide.app.ui.screens.workspace.EditorSession
import dev.easyide.app.ui.screens.workspace.EditorTab
import dev.easyide.app.ui.screens.workspace.KeyRowBar
import dev.easyide.app.ui.screens.workspace.WelcomeView
import dev.easyide.app.ui.screens.workspace.WorkspaceUiState
import dev.easyide.app.ui.screens.workspace.decor.DecorationRegistry
import dev.easyide.app.ui.screens.workspace.find.FindBar
import dev.easyide.app.ui.screens.workspace.find.FindController
import dev.easyide.app.ui.screens.workspace.lsp.NavLocation
import dev.easyide.app.ui.screens.workspace.lsp.SymbolRow
import dev.easyide.app.ui.shell.EditorGroup
import dev.easyide.app.ui.shell.Tab
import dev.easyide.app.ui.shell.TabState
import dev.easyide.app.ui.shell.host.DocumentStrip
import dev.easyide.app.ui.shell.workspace.BreadcrumbModel
import dev.easyide.app.ui.shell.workspace.Breadcrumbs
import dev.easyide.app.ui.shell.workspace.FileDocuments
import dev.easyide.app.ui.theme.ThemeMode
import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.extensions.contrib.RowKey
import dev.easyide.lsp.protocol.Position
import dev.easyide.lsp.protocol.Range
import dev.easyide.lsp.protocol.SymbolKind
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import dev.easyide.app.ui.screens.workspace.edit.SearchResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of the chrome around the editor text (document strip, breadcrumbs, find widget, the merged key
 * row, the welcome view) at the four [GoldenConfig]s, dark and light. Record with
 * `./gradlew :app:recordRoborazziDebug --tests '*EditorChromeGoldenTest'`; the PNGs land in
 * `src/test/screenshots/workspace`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1200dp-h1200dp-xhdpi")
class EditorChromeGoldenTest {
    @get:Rule val compose = createComposeRule()

    private var find: FindController? = null
    private val counted: Boolean get() = (find?.result as? SearchResult.Found)?.matches?.isNotEmpty() == true
    private val themes = listOf(ThemeMode.DARK, ThemeMode.LIGHT)
    private val path = "services/mobile/app/src/main/ui/screens/workspace/EditorPane.kt"

    @Test fun editorTabs() = compose.record("editor-tabs", themes) { c ->
        val names = listOf("Main.kt", "Theme.kt", "README.md", "AVeryLongFileNameThatMustEllipsizeInTheNarrowPanel.kt", "build.gradle.kts", "notes.txt")
        val tabs = names.mapIndexed { i, n -> Tab(FileDocuments.uriOf("src/$n")!!, if (i == 2) TabState.PREVIEW else TabState.KEPT) }
        val group = EditorGroup(tabs, active = tabs[1].key, mru = tabs.map { it.key })
        Box(Modifier.width(c.windowDp.dp)) {
            DocumentStrip(group, { it.uri.segments.last() }, { it == tabs[0] || it == tabs[4] }, {}, {}, {}, iconOf = { tab -> dev.easyide.app.ui.screens.workspace.files.FileIcon(tab.uri.segments.last(), size = dev.easyide.app.ui.kit.Kit.control.rowIcon) }) {
                KitIconButton(Icons.Filled.Visibility, "Preview", {})
                KitIconButton(Icons.Filled.VerticalSplit, "Split", {})
                KitIconButton(Icons.Filled.MoreVert, "More", {})
            }
        }
    }

    @Test fun breadcrumbs() = compose.record("breadcrumbs", themes) { c ->
        Column(Modifier.width(c.windowDp.dp)) {
            Breadcrumbs(path, emptyList(), { emptyList() }, {}, {}, {})
            Breadcrumbs(path, listOf(symbol("EditorPane", 0), symbol("EditorSurface", 1), symbol("onCaretChanged", 2)), { emptyList() }, {}, {}, {})
            Breadcrumbs("Main.kt", emptyList(), { emptyList() }, {}, {}, {})
        }
    }

    @Test fun findWidget() = compose.record("find-widget", themes, interact = { waitUntil(WAIT_MS) { counted } }) { c ->
        val find = rememberFind(replace = false)
        Box(Modifier.width(c.windowDp.dp).height(120.dp)) { FindBar(find, {}, Modifier.align(androidx.compose.ui.Alignment.TopEnd), c.width.isCompact) }
    }

    @Test fun findWidgetWithReplace() = compose.record("find-replace-widget", themes, interact = { waitUntil(WAIT_MS) { counted } }) { c ->
        val find = rememberFind(replace = true)
        Box(Modifier.width(c.windowDp.dp).height(160.dp)) { FindBar(find, {}, Modifier.align(androidx.compose.ui.Alignment.TopEnd), c.width.isCompact) }
    }

    @Test fun keyRow() = compose.record("key-row", themes) { c ->
        val keys = listOf("Tab", "<", ">", "^", "v", "{", "}", "(", ")", "[", "]", ";", "\"").map { RowKey(it, KeyAction.Insert(it), null) }
        Box(Modifier.width(c.windowDp.dp)) {
            KeyRowBar(keys, {}, trailing = { KitIconButton(dev.easyide.app.ui.icons.iconFor("more"), "More", {}) })
        }
    }

    @Test fun welcome() = compose.record("welcome", themes) { c ->
        val commands = listOf(CommandIds.QUICK_OPEN to R.string.command_quick_open, CommandIds.SHOW_COMMANDS to R.string.command_show_commands, CommandIds.FIND to R.string.command_find, CommandIds.SAVE to R.string.command_save)
        val registry = CommandRegistry(commands.map { (id, title) -> Command(id, title) {} })
        val keymap = Keymap(listOf(
            KeyBinding(KeyChord(KeyEvent.KEYCODE_P, ctrl = true), CommandIds.QUICK_OPEN, KeyFocus.ANYWHERE),
            KeyBinding(KeyChord(KeyEvent.KEYCODE_P, ctrl = true, shift = true), CommandIds.SHOW_COMMANDS, KeyFocus.ANYWHERE),
            KeyBinding(KeyChord(KeyEvent.KEYCODE_F, ctrl = true), CommandIds.FIND, KeyFocus.ANYWHERE),
            KeyBinding(KeyChord(KeyEvent.KEYCODE_S, ctrl = true), CommandIds.SAVE, KeyFocus.ANYWHERE),
        ))
        Box(Modifier.width(c.windowDp.dp).height(c.windowDp.dp.coerceAtMost(560.dp))) {
            WelcomeView(listOf("src/main/Main.kt", "docs/README.md", "build.gradle.kts"), registry, keymap, {}, onNewFile = {})
        }
    }

    private companion object { const val WAIT_MS = 10_000L }

    private fun symbol(name: String, depth: Int) =
        SymbolRow(name, null, SymbolKind.CLASS, depth, NavLocation(path, File(path), path, Range(Position(0, 0), Position(0, 1))))

    /** A real controller over an inert session, with a query already typed so the widget shows its counter. */
    @androidx.compose.runtime.Composable
    private fun rememberFind(replace: Boolean): FindController = androidx.compose.runtime.remember {
        val text = "val density = 1\nval other = density + density\n"
        val tab = EditorTab(path, "EditorPane.kt", text, text)
        val selections = EditorSelections()
        val find = FindController(
            CoroutineScope(Job() + Dispatchers.Unconfined), MutableStateFlow(WorkspaceUiState(openTabs = listOf(tab), activeTabPath = path)), selections,
            DecorationRegistry(), EditorSession(selections, { text }, { _, _ -> }, histories = EditHistories()),
        )
        find.open(replace)
        find.onQueryChange("density")
        // The search runs off the main thread; the shot waits for its counter (see [counted]).
        this.find = find
        find
    }
}
