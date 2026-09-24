package dev.easyide.app.extensions

import androidx.annotation.StringRes
import dev.easyide.app.R
import dev.easyide.app.ui.commands.CommandIds

/**
 * How one app command looks to packs: [title] as in the palette, plus the [shortTitle] and
 * easyIDE [icon] token a menu slot shows when a pack puts the command on a narrow surface
 * (touch toolbar, inline editor-title button). The command's behaviour and enabled state stay
 * with the app's `CommandRegistry`; this is presentation only.
 */
data class BuiltInCommandSpec(
    val id: String,
    @StringRes val title: Int,
    @StringRes val shortTitle: Int? = null,
    val icon: String? = null,
)

/**
 * Every built-in command registered as a built-in contribution, so a pack's menus,
 * keybindings and key rows can name it (MenuModel drops entries whose command is not in
 * the registry). Covers exactly [CommandIds.ALL]; a test holds the two together.
 */
object BuiltInCommandTable {
    val ALL: List<BuiltInCommandSpec> = listOf(
        BuiltInCommandSpec(CommandIds.SHOW_COMMANDS, R.string.command_show_commands, R.string.command_short_show_commands),
        BuiltInCommandSpec(CommandIds.SAVE, R.string.command_save, icon = ICON_SAVE),
        BuiltInCommandSpec(CommandIds.SAVE_ALL, R.string.command_save_all),
        BuiltInCommandSpec(CommandIds.CLOSE_EDITOR, R.string.command_close_editor),
        BuiltInCommandSpec(CommandIds.NEXT_EDITOR, R.string.command_next_editor),
        BuiltInCommandSpec(CommandIds.PREVIOUS_EDITOR, R.string.command_previous_editor),
        BuiltInCommandSpec(CommandIds.TOGGLE_MARKDOWN_PREVIEW, R.string.command_toggle_markdown_preview),
        BuiltInCommandSpec(CommandIds.TOGGLE_EXPLORER, R.string.command_toggle_explorer),
        BuiltInCommandSpec(CommandIds.TOGGLE_SOURCE_CONTROL, R.string.command_toggle_source_control),
        BuiltInCommandSpec(CommandIds.REFRESH_EXPLORER, R.string.command_refresh_explorer),
        BuiltInCommandSpec(CommandIds.TOGGLE_TERMINAL, R.string.command_toggle_terminal),
        BuiltInCommandSpec(CommandIds.NEW_TERMINAL, R.string.command_new_terminal),
        BuiltInCommandSpec(CommandIds.INSERT_SNIPPET, R.string.command_insert_snippet),
        BuiltInCommandSpec(CommandIds.RUN_TASK, R.string.command_run_task),
        BuiltInCommandSpec(CommandIds.SHOW_EXTENSIONS, R.string.command_show_extensions),
        BuiltInCommandSpec(CommandIds.TRIGGER_SUGGEST, R.string.command_trigger_suggest),
        BuiltInCommandSpec(CommandIds.TRIGGER_PARAMETER_HINTS, R.string.command_parameter_hints),
        BuiltInCommandSpec(CommandIds.SHOW_HOVER, R.string.command_show_hover, R.string.command_short_show_hover),
        BuiltInCommandSpec(CommandIds.REVEAL_DEFINITION, R.string.command_go_to_definition, R.string.command_short_go_to_definition),
        BuiltInCommandSpec(CommandIds.GO_TO_DECLARATION, R.string.command_go_to_declaration),
        BuiltInCommandSpec(CommandIds.GO_TO_TYPE_DEFINITION, R.string.command_go_to_type_definition),
        BuiltInCommandSpec(CommandIds.GO_TO_IMPLEMENTATION, R.string.command_go_to_implementation),
        BuiltInCommandSpec(CommandIds.GO_TO_REFERENCES, R.string.command_find_references, R.string.command_short_find_references),
        BuiltInCommandSpec(CommandIds.RENAME, R.string.command_rename_symbol, R.string.command_short_rename_symbol),
        BuiltInCommandSpec(CommandIds.QUICK_FIX, R.string.command_quick_fix, R.string.command_short_quick_fix),
        BuiltInCommandSpec(CommandIds.FORMAT_DOCUMENT, R.string.command_format_document, R.string.command_short_format_document),
        BuiltInCommandSpec(CommandIds.FORMAT_SELECTION, R.string.command_format_selection, R.string.command_short_format_selection),
        BuiltInCommandSpec(CommandIds.TOGGLE_LINE_COMMENT, R.string.command_toggle_line_comment, R.string.command_short_toggle_line_comment),
        BuiltInCommandSpec(CommandIds.GOTO_SYMBOL, R.string.command_go_to_symbol, R.string.command_short_go_to_symbol),
        BuiltInCommandSpec(CommandIds.SHOW_ALL_SYMBOLS, R.string.command_show_all_symbols),
        BuiltInCommandSpec(CommandIds.SHOW_PROBLEMS, R.string.command_show_problems),
        BuiltInCommandSpec(CommandIds.SHOW_OUTLINE, R.string.command_show_outline),
        BuiltInCommandSpec(CommandIds.RESTART_LANGUAGE_SERVERS, R.string.command_restart_servers),
    )
}

/** A token of the app's command icon table (the `ui/icons` resolver). */
private const val ICON_SAVE = "save"
