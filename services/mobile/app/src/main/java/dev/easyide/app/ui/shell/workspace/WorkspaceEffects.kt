package dev.easyide.app.ui.shell.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.easyide.app.ui.screens.workspace.ext.StageAccess
import dev.easyide.app.ui.screens.workspace.lsp.LspPanel
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.ShellState
import dev.easyide.extensions.contrib.StagePlacement

/** The shell as the extension host sees it: which stages show, and a request to show one. */
class ShellStageAccess(private val state: ShellState?, private val model: WorkspaceShellModel) : StageAccess {
    private fun open(p: Placement) = state?.current?.layout?.isOpen(p) == true
    override val leftVisible: Boolean get() = open(Placement.SIDEBAR)
    override val rightVisible: Boolean get() = open(Placement.SECONDARY_SIDEBAR)
    override val bottomVisible: Boolean get() = open(Placement.PANEL)

    override fun show(placement: StagePlacement) {
        when (placement) {
            StagePlacement.LEFT -> model.dispatch(dev.easyide.app.ui.shell.ShellAction.ShowPanel(Placement.SIDEBAR))
            StagePlacement.RIGHT -> model.dispatch(dev.easyide.app.ui.shell.ShellAction.ShowPanel(Placement.SECONDARY_SIDEBAR))
            StagePlacement.BOTTOM -> model.reveal(CoreShell.TERMINAL)
            StagePlacement.MAIN -> Unit
        }
    }
}

/**
 * The couplings between the shell and the workspace's own state, each a rule that has to hold whoever
 * moved first: the stage's file documents follow the view model's tabs; the language panel the
 * status bar toggles is the shell's Problems or Outline container; opening source control reads git
 * again; an install run that asks for the terminal gets it.
 */
@Composable
fun WorkspaceEffects(model: WorkspaceShellModel, env: WorkspaceEnv) {
    val state by model.state.collectAsState()
    val ready = state != null
    val ui = env.ui

    val openPaths = ui.openTabs.map { it.relativePath }
    LaunchedEffect(openPaths, ui.activeTabPath, ready) {
        val stage = model.state.value?.current?.stage ?: return@LaunchedEffect
        FileTabSync.plan(openPaths, ui.activeTabPath, stage).forEach(model::dispatch)
    }

    LaunchedEffect(ui.terminalRevealRequests) { if (ui.terminalRevealRequests > 0) model.reveal(CoreShell.TERMINAL) }

    val layout = state?.current?.layout
    val scmShown = layout?.isOpen(Placement.SIDEBAR) == true && layout.container(Placement.SIDEBAR) == CoreShell.SOURCE_CONTROL
    LaunchedEffect(scmShown) { if (scmShown) env.gitCallbacks.onRefresh() }

    LanguagePanelBridge(model, env, state)
}

/**
 * The language servers' Problems, References and Outline are one controller state (`lsp.panel`) that
 * the status bar and commands toggle; in the shell they are two containers (Problems in the bottom
 * panel, Outline on the right). The controller opening a panel reveals its container; a container
 * shown reveals its tab; the controller closing the panel (its own close button) closes the container.
 */
@Composable
private fun LanguagePanelBridge(model: WorkspaceShellModel, env: WorkspaceEnv, state: ShellState?) {
    val lsp = env.lsp
    val open by lsp.panel.collectAsState()
    val layout = state?.current?.layout
    val problemsShown = layout?.isOpen(Placement.PANEL) == true && layout.container(Placement.PANEL) == CoreShell.PROBLEMS_PANEL
    val outlineShown = layout?.isOpen(Placement.SECONDARY_SIDEBAR) == true && layout.container(Placement.SECONDARY_SIDEBAR) == CoreShell.OUTLINE
    var was by remember { mutableStateOf(open) }
    LaunchedEffect(open) {
        when {
            open == LspPanel.OUTLINE -> model.reveal(CoreShell.OUTLINE)
            open != null -> model.reveal(CoreShell.PROBLEMS_PANEL)
            was != null -> {
                if (problemsShown) model.toggle(Placement.PANEL)
                if (outlineShown) model.toggle(Placement.SECONDARY_SIDEBAR)
            }
        }
        was = open
    }
    LaunchedEffect(problemsShown, outlineShown) {
        when {
            outlineShown -> lsp.showPanel(LspPanel.OUTLINE)
            problemsShown -> if (open == null || open == LspPanel.OUTLINE) lsp.showPanel(LspPanel.PROBLEMS)
            else -> lsp.showPanel(null)
        }
    }
}
