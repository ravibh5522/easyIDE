package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.app.R
import dev.easyide.app.ui.commands.Command
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.lsp.protocol.LspFeature

/**
 * The LSP commands of the palette and keymap (VS Code ids). Enablement is
 * `lspSupports:<lang>:<feature>` for the active document (lsp-features.md 4.5): a command whose
 * feature no running server offers stays listed but greyed, and its chord does nothing.
 */
fun lspCommands(controller: WorkspaceLspController): List<Command> {
    fun on(feature: LspFeature) = controller.supports(feature)
    val nav = controller.navigation
    return listOf(
        Command(CommandIds.TRIGGER_SUGGEST, R.string.command_trigger_suggest, on(LspFeature.COMPLETION)) { controller.completion.trigger() },
        Command(CommandIds.TRIGGER_PARAMETER_HINTS, R.string.command_parameter_hints, on(LspFeature.SIGNATURE_HELP)) {
            controller.info.triggerSignature()
        },
        Command(CommandIds.SHOW_HOVER, R.string.command_show_hover, on(LspFeature.HOVER)) { controller.showHoverAtCaret() },
        Command(CommandIds.REVEAL_DEFINITION, R.string.command_go_to_definition, on(LspFeature.DEFINITION)) { nav.goTo(NavKind.DEFINITION) },
        Command(CommandIds.GO_TO_DECLARATION, R.string.command_go_to_declaration, on(LspFeature.DECLARATION)) { nav.goTo(NavKind.DECLARATION) },
        Command(CommandIds.GO_TO_TYPE_DEFINITION, R.string.command_go_to_type_definition, on(LspFeature.TYPE_DEFINITION)) {
            nav.goTo(NavKind.TYPE_DEFINITION)
        },
        Command(CommandIds.GO_TO_IMPLEMENTATION, R.string.command_go_to_implementation, on(LspFeature.IMPLEMENTATION)) {
            nav.goTo(NavKind.IMPLEMENTATION)
        },
        Command(CommandIds.GO_TO_REFERENCES, R.string.command_find_references, on(LspFeature.REFERENCES)) {
            controller.openLocations(NavKind.REFERENCES)
        },
        Command(CommandIds.RENAME, R.string.command_rename_symbol, on(LspFeature.RENAME)) { nav.startRename() },
        Command(CommandIds.QUICK_FIX, R.string.command_quick_fix, on(LspFeature.CODE_ACTION)) { controller.actions.openMenuAtCaret() },
        Command(CommandIds.FORMAT_DOCUMENT, R.string.command_format_document, on(LspFeature.FORMATTING)) {
            controller.actions.formatDocument()
        },
        Command(CommandIds.FORMAT_SELECTION, R.string.command_format_selection, on(LspFeature.RANGE_FORMATTING)) {
            controller.actions.formatDocument(selectionOnly = true)
        },
        Command(CommandIds.GOTO_SYMBOL, R.string.command_go_to_symbol, on(LspFeature.DOCUMENT_SYMBOL)) { nav.openPicker(SymbolScope.DOCUMENT) },
        Command(CommandIds.SHOW_ALL_SYMBOLS, R.string.command_show_all_symbols, controller.supportsAnyOpen(LspFeature.WORKSPACE_SYMBOL)) { nav.openPicker(SymbolScope.WORKSPACE) },
        Command(CommandIds.SHOW_PROBLEMS, R.string.command_show_problems) { controller.togglePanel(LspPanel.PROBLEMS) },
        Command(CommandIds.SHOW_OUTLINE, R.string.command_show_outline) { controller.togglePanel(LspPanel.OUTLINE) },
        Command(CommandIds.RESTART_LANGUAGE_SERVERS, R.string.command_restart_servers, controller.statuses.value.isNotEmpty()) {
            controller.statuses.value.forEach { controller.restart(it.key) }
        },
    )
}
