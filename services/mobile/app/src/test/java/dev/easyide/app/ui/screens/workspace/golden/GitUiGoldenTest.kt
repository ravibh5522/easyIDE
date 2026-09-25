package dev.easyide.app.ui.screens.workspace.golden

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import dev.easyide.app.ui.screens.workspace.git.RepositoryBody
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitMenuCascade
import dev.easyide.app.ui.kit.MenuNav
import dev.easyide.app.ui.props.Appearance
import dev.easyide.app.ui.screens.workspace.FileTreePane
import dev.easyide.app.ui.screens.workspace.InlineEditSpec
import dev.easyide.app.ui.screens.workspace.SourceControlPane
import dev.easyide.app.ui.screens.workspace.fileMenuItems
import dev.easyide.app.ui.screens.workspace.git.CommitMenuEnv
import dev.easyide.app.ui.screens.workspace.git.CommitMode
import dev.easyide.app.ui.screens.workspace.git.CommitGraph
import dev.easyide.app.ui.screens.workspace.git.commitMenuItems
import dev.easyide.app.ui.screens.workspace.git.commitModeItems
import dev.easyide.app.ui.shell.DocumentOpener
import dev.easyide.app.ui.theme.EasyIdeTheme
import dev.easyide.app.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of the source control pane, its two menus and the explorer with its context menu, at 1152dp dense,
 * 411dp comfortable and 320dp with font scale 2, dark and light. Menus are laid out inline (the same panels
 * the popup shows) because a popup window is not part of the captured root. Record with
 * `./gradlew :app:recordRoborazziDebug --tests '*GitUiGoldenTest'`; PNGs land in `src/test/screenshots/gitui`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1200dp-h1200dp-xhdpi")
class GitUiGoldenTest {
    @get:Rule val compose = createComposeRule()

    private val opener = DocumentOpener({ _, _ -> }, {})
    private val configs = listOf(GoldenConfig.ExpandedDense, GoldenConfig.CompactComfortable, GoldenConfig.CompactStress)

    @Test fun sourceControl() = compose.recordThemed("source-control") { c ->
        SourceControlPane(GitUiFixtures.panel, GoldenSamples.gitCallbacks, opener, Modifier.width(c.panel()).height(PANE_HEIGHT))
    }

    @Test fun changesAsTree() = compose.recordThemed("changes-tree") { c ->
        val panel = GitUiFixtures.panel
        androidx.compose.foundation.layout.Column(Modifier.width(c.panel()).height(PANE_HEIGHT).background(Kit.colors.panel)) {
            RepositoryBody(panel, panel.status!!, CommitGraph.build(panel.commits), GoldenSamples.gitCallbacks, opener, treeView = true)
        }
    }

    @Test fun selectedChangeAndCommit() = compose.recordThemed("selected-rows", interact = {
        onNodeWithText("ChangeRow.kt").performClick()
        mainClock.advanceTimeBy(CLICK_SETTLE_MS)
    }) { c ->
        SourceControlPane(GitUiFixtures.panel, GoldenSamples.gitCallbacks, opener, Modifier.width(c.panel()).height(PANE_HEIGHT))
    }

    @Test fun commitSplitMenu() = compose.recordThemed("commit-split-menu") { c ->
        Box(Modifier.width(c.panel() + CASCADE_WIDTH).height(PANE_HEIGHT)) {
            SourceControlPane(GitUiFixtures.panel, GoldenSamples.gitCallbacks, opener, Modifier.width(c.panel()).height(PANE_HEIGHT))
            Box(Modifier.offset(x = MENU_X, y = SPLIT_MENU_Y)) {
                KitMenuCascade(commitModeItems({ true }, hasRemote = true) {}, MenuNav(listOf(0)))
            }
        }
    }

    @Test fun commitContextMenu() = compose.recordThemed("commit-context-menu") { c ->
        val graph = CommitGraph.build(GitUiFixtures.commits)
        val row = graph.first { it.commit.id == "f1000001" }
        val env = CommitMenuEnv(GoldenSamples.gitCallbacks.git, hasUpstream = true, busy = false, onOpenChanges = {}, onAsk = {})
        val items = commitMenuItems(row, GitUiFixtures.refs.getValue("f1000001"), env)
        Box(Modifier.width(c.panel() + CASCADE_WIDTH).height(PANE_HEIGHT)) {
            SourceControlPane(GitUiFixtures.panel, GoldenSamples.gitCallbacks, opener, Modifier.width(c.panel()).height(PANE_HEIGHT))
            Box(Modifier.offset(x = MENU_X, y = GRAPH_MENU_Y)) { KitMenuCascade(items, MenuNav(listOf(2, 0))) }
        }
    }

    @Test fun explorerContextMenu() = compose.recordThemed("explorer-context-menu") { c ->
        Box(Modifier.width(c.panel() + CASCADE_WIDTH)) {
            FileTreePane(GoldenSamples.tree, {}, {}, { _, _ -> }, {}, {}, {}, InlineEditSpec.NONE, Modifier.width(c.panel()).height(PANE_HEIGHT), GoldenSamples.treeChanges)
            Box(Modifier.offset(x = MENU_X, y = TREE_MENU_Y)) {
                KitMenuCascade(fileMenuItems(GitUiFixtures.menuFile, canPaste = true, onAction = { _, _ -> }), MenuNav(listOf(0)))
            }
        }
    }

    @Test fun explorer() = compose.recordThemed("explorer") { c ->
        FileTreePane(GoldenSamples.tree, {}, {}, { _, _ -> }, {}, {}, {}, InlineEditSpec.NONE, Modifier.width(c.panel()).height(TREE_HEIGHT), GoldenSamples.treeChanges)
    }

    /** One PNG per window configuration and theme: `<name>-<config>-<dark|light>.png`. */
    private fun ComposeContentTestRule.recordThemed(name: String, interact: ComposeContentTestRule.() -> Unit = {}, content: @Composable (GoldenConfig) -> Unit) {
        var config by mutableStateOf(configs.first())
        var mode by mutableStateOf(ThemeMode.DARK)
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, config.fontScale)) {
                EasyIdeTheme(themeMode = mode, appearance = Appearance(density = config.density), width = config.width) {
                    Box(Modifier.background(Kit.colors.background)) { content(config) }
                }
            }
        }
        waitForIdle()
        interact()
        for (m in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            for (c in configs) {
                config = c
                mode = m
                waitForIdle()
                onRoot().captureRoboImage("src/test/screenshots/gitui/$name-${c.id}-${m.name.lowercase()}.png")
            }
        }
    }

    private companion object {
        val PANE_HEIGHT = 760.dp
        val TREE_HEIGHT = 300.dp
        val MENU_X = 60.dp
        val SPLIT_MENU_Y = 150.dp
        val GRAPH_MENU_Y = 420.dp
        val TREE_MENU_Y = 60.dp
        val CASCADE_WIDTH = 260.dp

        /** A row with a double tap waits this long for the second tap before a single tap counts. */
        const val CLICK_SETTLE_MS = 500L
    }
}
