package dev.easyide.app.ui.commands

import androidx.annotation.StringRes

/**
 * One user-invokable action. The palette, the keymap and (later) menus and key
 * rows are views over the same list, so an action is defined exactly once.
 * [enabled] is evaluated when the list is built; a disabled command stays
 * listed but greyed, and its chord is consumed without running it.
 */
class Command(
    val id: String,
    @StringRes val title: Int,
    val enabled: Boolean = true,
    val run: () -> Unit,
)

class CommandRegistry(val commands: List<Command>) {

    private val byId: Map<String, Command> = commands.associateBy { it.id }

    operator fun get(id: String): Command? = byId[id]

    /** Runs [id] if registered and enabled; true when [id] is registered at all. */
    fun execute(id: String): Boolean {
        val command = byId[id] ?: return false
        if (command.enabled) command.run()
        return true
    }
}

/**
 * Command ids. VS Code's names where one exists, so extension keybindings and
 * `when` clauses written for VS Code resolve to the same actions.
 */
object CommandIds {
    const val SHOW_COMMANDS = "workbench.action.showCommands"
    const val SAVE = "workbench.action.files.save"
    const val SAVE_ALL = "workbench.action.files.saveAll"
    const val CLOSE_EDITOR = "workbench.action.closeActiveEditor"
    const val NEXT_EDITOR = "workbench.action.nextEditor"
    const val PREVIOUS_EDITOR = "workbench.action.previousEditor"
    const val TOGGLE_EXPLORER = "workbench.view.explorer"
    const val TOGGLE_SOURCE_CONTROL = "workbench.view.scm"
    const val TOGGLE_TERMINAL = "workbench.action.terminal.toggleTerminal"
    const val NEW_TERMINAL = "workbench.action.terminal.new"
    const val REFRESH_EXPLORER = "workbench.files.action.refreshFilesExplorer"
    const val TOGGLE_MARKDOWN_PREVIEW = "markdown.togglePreview"

    const val TRIGGER_SUGGEST = "editor.action.triggerSuggest"
    const val TRIGGER_PARAMETER_HINTS = "editor.action.triggerParameterHints"
    const val SHOW_HOVER = "editor.action.showHover"
    const val REVEAL_DEFINITION = "editor.action.revealDefinition"
    const val GO_TO_DECLARATION = "editor.action.revealDeclaration"
    const val GO_TO_TYPE_DEFINITION = "editor.action.goToTypeDefinition"
    const val GO_TO_IMPLEMENTATION = "editor.action.goToImplementation"
    const val GO_TO_REFERENCES = "editor.action.goToReferences"
    const val RENAME = "editor.action.rename"
    const val QUICK_FIX = "editor.action.quickFix"
    const val FORMAT_DOCUMENT = "editor.action.formatDocument"
    const val FORMAT_SELECTION = "editor.action.formatSelection"
    const val GOTO_SYMBOL = "workbench.action.gotoSymbol"
    const val SHOW_ALL_SYMBOLS = "workbench.action.showAllSymbols"
    const val SHOW_PROBLEMS = "workbench.actions.view.problems"
    const val SHOW_OUTLINE = "outline.focus"
    const val RESTART_LANGUAGE_SERVERS = "easyide.lsp.restartServers"

    /** Every built-in id: keybindings.json entries naming anything else get an "unknown command" warning. */
    val ALL: Set<String> = setOf(
        SHOW_COMMANDS, SAVE, SAVE_ALL, CLOSE_EDITOR, NEXT_EDITOR, PREVIOUS_EDITOR, TOGGLE_EXPLORER,
        TOGGLE_SOURCE_CONTROL, TOGGLE_TERMINAL, NEW_TERMINAL, REFRESH_EXPLORER, TOGGLE_MARKDOWN_PREVIEW,
        TRIGGER_SUGGEST, TRIGGER_PARAMETER_HINTS, SHOW_HOVER, REVEAL_DEFINITION, GO_TO_DECLARATION,
        GO_TO_TYPE_DEFINITION, GO_TO_IMPLEMENTATION, GO_TO_REFERENCES, RENAME, QUICK_FIX, FORMAT_DOCUMENT,
        FORMAT_SELECTION, GOTO_SYMBOL, SHOW_ALL_SYMBOLS, SHOW_PROBLEMS, SHOW_OUTLINE, RESTART_LANGUAGE_SERVERS,
    )
}
