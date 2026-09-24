package dev.easyide.app.ui.screens.workspace.golden

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.screens.workspace.FileTreePane
import dev.easyide.app.ui.screens.workspace.InlineEdit
import dev.easyide.app.ui.screens.workspace.InlineEditSpec
import dev.easyide.app.ui.screens.workspace.SourceControlPane
import dev.easyide.app.ui.screens.workspace.lsp.LspPanel
import dev.easyide.app.ui.screens.workspace.lsp.LspPanelFrame
import dev.easyide.app.ui.screens.workspace.lsp.ProblemsView
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.DocumentOpener
import dev.easyide.app.ui.shell.EditorGroup
import dev.easyide.app.ui.shell.NavEnv
import dev.easyide.app.ui.shell.NavPrefs
import dev.easyide.app.ui.shell.ShellScope
import dev.easyide.app.ui.shell.Tab
import dev.easyide.app.ui.shell.TabState
import dev.easyide.app.ui.shell.host.DocumentStrip
import dev.easyide.app.ui.shell.nav.NavPlacement
import dev.easyide.app.ui.shell.nav.NavSurface
import dev.easyide.app.ui.shell.nav.NavSurfaceState
import dev.easyide.app.ui.shell.workspace.FileDocuments
import dev.easyide.app.ui.shell.workspace.StatusParts
import dev.easyide.app.ui.shell.workspace.StatusStrip
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of the workspace chrome and panels at the three configurations of density.md 4 (expanded dense,
 * medium dense, compact comfortable) and a 320dp, font scale 2 stress case. Record:
 * `./gradlew :app:recordRoborazziDebug --tests '*WorkspaceGoldenTest'`; the PNGs are in
 * `src/test/screenshots/workspace`, read them to judge alignment, wrapping and type sizes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1200dp-h1200dp-xhdpi")
class WorkspaceGoldenTest {
    @get:Rule val compose = createComposeRule()

    private val opener = DocumentOpener({ _, _ -> }, {})

    @Test fun fileTree() = compose.record("file-tree") { c ->
        FileTreePane(GoldenSamples.tree, {}, {}, { _, _ -> }, {}, {}, {}, InlineEditSpec.NONE, Modifier.width(c.panel()).height(300.dp), GoldenSamples.treeChanges)
    }

    @Test fun fileTreeInlineCreate() = compose.record("file-tree-inline") { c ->
        val inline = InlineEditSpec(InlineEdit.NewFile("src/main"), { _, _ -> }, {})
        FileTreePane(GoldenSamples.tree, {}, {}, { _, _ -> }, {}, {}, {}, inline, Modifier.width(c.panel()).height(300.dp), GoldenSamples.treeChanges)
    }

    @Test fun sourceControl() = compose.record("source-control") { c ->
        SourceControlPane(GoldenSamples.git, GoldenSamples.gitCallbacks, opener, Modifier.width(c.panel()).height(520.dp))
    }

    @Test fun sourceControlSelectedRow() = compose.record("source-control-selected", interact = { onNodeWithText("FileTreeRow.kt").performClick(); mainClock.advanceTimeBy(CLICK_SETTLE_MS) }) { c ->
        SourceControlPane(GoldenSamples.git, GoldenSamples.gitCallbacks, opener, Modifier.width(c.panel()).height(520.dp))
    }

    @Test fun problems() = compose.record("problems") { c ->
        Box(Modifier.width(c.panel()).height(300.dp)) {
            LspPanelFrame(LspPanel.PROBLEMS, {}, {}) { ProblemsView(GoldenSamples.problems) {} }
        }
    }

    @Test fun rail() = compose.record("rail") { c ->
        val compact = c.width.isCompact
        val surface = NavSurfaceState(
            if (compact) NavPlacement.BOTTOM else NavPlacement.RAIL_START, navItems(), CoreShell.FILES,
            emptySet(), showLabels = compact, badges = emptyMap(), focus = FocusRequester(),
        )
        Box(if (compact) Modifier.width(c.windowDp.dp) else Modifier.height(640.dp)) { NavSurface(surface, {}) }
    }

    @Test fun documentStrip() = compose.record("document-strip") { c ->
        val tabs = listOf(
            Tab(FileDocuments.uriOf("src/main/Main.kt")!!), Tab(FileDocuments.uriOf("src/main/Theme.kt")!!),
            Tab(FileDocuments.uriOf("docs/README.md")!!, TabState.PREVIEW), Tab(FileDocuments.uriOf("src/main/AVeryLongFileNameThatMustEllipsizeInTheNarrowPanel.kt")!!),
        )
        val group = EditorGroup(tabs, active = tabs[1].key, mru = tabs.map { it.key })
        Box(Modifier.width(c.windowDp.dp)) {
            DocumentStrip(group, { it.uri.segments.last() }, { it == tabs[0] }, {}, {}, {}) {
                KitIconButton(Icons.Filled.Visibility, "Preview", {})
                KitIconButton(Icons.Filled.VerticalSplit, "Split", {})
                KitIconButton(Icons.Filled.MoreVert, "More", {})
            }
        }
    }

    @Test fun statusStrip() = compose.record("status-strip") { c ->
        val text = Kit.text.caption.copy(color = Kit.colors.statusBarText)
        val parts = StatusParts(
            project = "easyide", file = "Main.kt", lines = 214, dirty = true, onSave = {}, extLeft = {}, extRight = {},
            problems = { BasicText("2 errors  5 warnings", style = text, maxLines = 1) },
        )
        Row(Modifier.width(c.windowDp.dp), verticalAlignment = Alignment.CenterVertically) { StatusStrip(parts, c.width, Modifier.fillMaxWidth()) }
    }

    private companion object {
        /** A row with a double tap waits this long for the second tap before a single tap counts. */
        const val CLICK_SETTLE_MS = 500L
    }

    private fun navItems() = CoreShell.navigation().visible(ShellScope.WORKSPACE, NavPrefs(), NavEnv({ false }, { true }))
}
