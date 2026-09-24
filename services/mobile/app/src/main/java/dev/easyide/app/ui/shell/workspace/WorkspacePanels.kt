package dev.easyide.app.ui.shell.workspace

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.easyide.app.ui.screens.workspace.FileTreePane
import dev.easyide.app.ui.screens.workspace.SourceControlPane
import dev.easyide.app.ui.screens.workspace.TerminalPane
import dev.easyide.app.ui.screens.workspace.lsp.LspPanel
import dev.easyide.app.ui.screens.workspace.lsp.LspSidePanel
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.host.DocumentRendererRegistry
import dev.easyide.app.ui.shell.host.PanelBinding
import dev.easyide.app.ui.shell.host.PanelRendererRegistry
import dev.easyide.app.ui.shell.host.panelRenderer
import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.app.ui.screens.workspace.TerminalKeyInput

/**
 * The one place the workspace's containers and document types are bound to their composables. The
 * panels are the existing ones (the tree, source control, the language panels, the terminal) called
 * as they are; each reads what it needs from [LocalWorkspaceEnv]. Extensions and Settings are not
 * here: their panels and pages are the app shell's, and the workspace registries add them.
 */
object WorkspacePanels {
    private val bindings: Map<String, PanelBinding> = mapOf(
        CoreShell.EXPLORER to PanelBinding(explorer()),
        CoreShell.SEARCH_PANEL to PanelBinding(panelRenderer { m -> SearchPanel(LocalWorkspaceEnv.current, m) }),
        CoreShell.SOURCE_CONTROL to PanelBinding(sourceControl()),
        CoreShell.OUTLINE to PanelBinding(languagePanel(LspPanel.OUTLINE)),
        CoreShell.PROBLEMS_PANEL to PanelBinding(languagePanel(LspPanel.PROBLEMS)),
        CoreShell.TERMINAL to PanelBinding(terminal()),
    )

    val containerIds: Set<String> get() = bindings.keys

    fun panels(): PanelRendererRegistry = PanelRendererRegistry(bindings)

    fun documents(): DocumentRendererRegistry = DocumentRendererRegistry(mapOf(FileDocuments.TYPE_ID to fileDocument()))

    private fun explorer() = panelRenderer { modifier ->
        val env = LocalWorkspaceEnv.current
        FileTreePane(
            state = env.ui,
            onFileOpened = env.callbacks.onFileOpened,
            onDirectoryToggled = env.callbacks.onDirectoryToggled,
            onNodeMenu = env.actions.onNodeMenu,
            onNewFile = env.actions.onNewFile,
            onNewFolder = env.actions.onNewFolder,
            onRefresh = env.callbacks.onRefreshTree,
            modifier = modifier.fillMaxSize(),
        )
    }

    private fun sourceControl() = panelRenderer { modifier ->
        val env = LocalWorkspaceEnv.current
        SourceControlPane(env.git, env.gitCallbacks, modifier.fillMaxSize())
    }

    /** The language panel shows whichever tab the controller has open; [default] is the one this container is for. */
    private fun languagePanel(default: LspPanel) = panelRenderer { modifier ->
        val env = LocalWorkspaceEnv.current
        val open by env.lsp.panel.collectAsState()
        LspSidePanel(env.lsp, open ?: default, modifier.fillMaxSize())
    }

    /** The key row of the terminal is the input dock's, so the pane is given none of its own. */
    private fun terminal() = panelRenderer { modifier ->
        val env = LocalWorkspaceEnv.current
        val ui = env.ui
        TerminalPane(
            tabs = ui.terminals,
            activeTabId = ui.activeTerminalId,
            linuxReady = ui.linuxReady,
            isInstalling = ui.isInstalling,
            onNewTab = env.callbacks.onNewTerminal,
            onSelectTab = env.callbacks.onSelectTerminal,
            onCloseTab = env.callbacks.onCloseTerminal,
            onRenameTab = env.actions.onRenameTerminal,
            onInstallLinux = env.callbacks.onInstallLinux,
            onHardwareKey = env.actions.onTerminalKey,
            modifier = modifier.fillMaxSize(),
        )
    }
}

/** What a terminal key row key does: a command runs, anything else is bytes for the shell. */
fun terminalRowKey(action: KeyAction, session: com.termux.terminal.TerminalSession, runCommand: (String) -> Unit) {
    if (action is KeyAction.Command) runCommand(action.id) else TerminalKeyInput.bytesFor(action, session)?.let(session::write)
}
