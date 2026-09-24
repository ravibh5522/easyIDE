package dev.easyide.app.ui.screens.workspace

import dev.easyide.app.R
import dev.easyide.app.ui.commands.Command
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.app.ui.commands.CommandRegistry

/** Screen-owned actions (stage, overlay and dialog state) that commands route to. */
class WorkspaceShellActions(
    val toggleExplorer: () -> Unit,
    val toggleSourceControl: () -> Unit,
    val toggleTerminal: () -> Unit,
    val showCommands: () -> Unit,
    /** Close with the unsaved-changes guard, not the raw ViewModel close. */
    val closeTab: (String) -> Unit,
    val insertSnippet: () -> Unit,
    val runTask: () -> Unit,
    val showExtensions: () -> Unit,
)

/**
 * The workspace's commands, rebuilt per composition so `enabled` and the tab
 * order reflect the current state. Everything routes to existing ViewModel
 * callbacks or stage toggles; no command owns logic of its own.
 */
fun workspaceCommands(
    uiState: WorkspaceUiState,
    callbacks: WorkspaceCallbacks,
    shell: WorkspaceShellActions,
    /** Commands of other features (language servers), appended after the workspace's own. */
    extra: List<Command> = emptyList(),
): CommandRegistry {
    val active = uiState.activeTab
    val tabs = uiState.openTabs

    fun cycleTab(step: Int) {
        val index = tabs.indexOfFirst { it.relativePath == uiState.activeTabPath }
        callbacks.onTabSelected(tabs[Math.floorMod(index + step, tabs.size)].relativePath)
    }

    return CommandRegistry(
        listOf(
            Command(CommandIds.SHOW_COMMANDS, R.string.command_show_commands, run = shell.showCommands),
            Command(CommandIds.SAVE, R.string.command_save, enabled = active != null, run = callbacks.onSave),
            Command(CommandIds.SAVE_ALL, R.string.command_save_all, enabled = tabs.any { it.isDirty }) {
                callbacks.onSaveTabs(tabs.map { it.relativePath }) {}
            },
            Command(CommandIds.CLOSE_EDITOR, R.string.command_close_editor, enabled = active != null) {
                active?.let { shell.closeTab(it.relativePath) }
            },
            Command(CommandIds.NEXT_EDITOR, R.string.command_next_editor, enabled = tabs.size > 1) { cycleTab(1) },
            Command(CommandIds.PREVIOUS_EDITOR, R.string.command_previous_editor, enabled = tabs.size > 1) {
                cycleTab(-1)
            },
            Command(
                CommandIds.TOGGLE_MARKDOWN_PREVIEW,
                R.string.command_toggle_markdown_preview,
                enabled = active?.isMarkdown == true,
                run = callbacks.onTogglePreview,
            ),
            Command(CommandIds.TOGGLE_EXPLORER, R.string.command_toggle_explorer, run = shell.toggleExplorer),
            Command(
                CommandIds.TOGGLE_SOURCE_CONTROL,
                R.string.command_toggle_source_control,
                run = shell.toggleSourceControl,
            ),
            Command(CommandIds.REFRESH_EXPLORER, R.string.command_refresh_explorer, run = callbacks.onRefreshTree),
            Command(CommandIds.TOGGLE_TERMINAL, R.string.command_toggle_terminal, run = shell.toggleTerminal),
            Command(CommandIds.NEW_TERMINAL, R.string.command_new_terminal, run = callbacks.onNewTerminal),
            Command(CommandIds.INSERT_SNIPPET, R.string.command_insert_snippet, enabled = active?.editable == true, run = shell.insertSnippet),
            Command(CommandIds.RUN_TASK, R.string.command_run_task, run = shell.runTask),
            Command(CommandIds.SHOW_EXTENSIONS, R.string.command_show_extensions, run = shell.showExtensions),
        ) + extra,
    )
}
