package dev.easyide.app.ui.screens.workspace.ext

import dev.easyide.app.extensions.adapters.EditorText
import dev.easyide.app.ui.screens.workspace.edit.LanguageConfig
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

/**
 * The VS Code snippet variables (`TM_*`, `CURRENT_*`, comment tokens) for one insertion
 * at [from]..[to] in [text]. `CLIPBOARD` is not offered: reading the clipboard needs the
 * `clipboard` capability, which a snippet body does not carry. Unknown names return null
 * (the body's default, or the name, is inserted instead).
 */
internal class SnippetVariables(
    private val fileName: String,
    private val guestPath: String,
    private val workspaceName: String,
    private val text: String,
    private val from: Int,
    private val to: Int,
    private val comments: LanguageConfig,
    private val now: LocalDateTime = LocalDateTime.now(),
) {
    fun resolve(name: String): String? = when (name) {
        "TM_SELECTED_TEXT" -> text.substring(from, to)
        "TM_CURRENT_LINE" -> EditorText.lineAt(text, from)
        "TM_CURRENT_WORD" -> EditorText.wordAt(text, from)
        "TM_LINE_INDEX" -> text.substring(0, from).count { it == '\n' }.toString()
        "TM_LINE_NUMBER" -> (text.substring(0, from).count { it == '\n' } + 1).toString()
        "TM_FILENAME" -> fileName
        "TM_FILENAME_BASE" -> fileName.substringBeforeLast('.', fileName)
        "TM_DIRECTORY" -> guestPath.substringBeforeLast('/')
        "TM_FILEPATH" -> guestPath
        "RELATIVE_FILEPATH" -> guestPath.removePrefix(WORKSPACE_PREFIX)
        "WORKSPACE_NAME" -> workspaceName
        "WORKSPACE_FOLDER" -> WORKSPACE
        "CURRENT_YEAR" -> now.year.toString()
        "CURRENT_YEAR_SHORT" -> (now.year % CENTURY).toString().padStart(2, '0')
        "CURRENT_MONTH" -> two(now.monthValue)
        "CURRENT_DATE" -> two(now.dayOfMonth)
        "CURRENT_HOUR" -> two(now.hour)
        "CURRENT_MINUTE" -> two(now.minute)
        "CURRENT_SECOND" -> two(now.second)
        "CURRENT_MONTH_NAME" -> now.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
        "CURRENT_DAY_NAME" -> now.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
        "UUID" -> UUID.randomUUID().toString()
        "LINE_COMMENT" -> comments.lineComment
        "BLOCK_COMMENT_START" -> comments.blockComment?.first
        "BLOCK_COMMENT_END" -> comments.blockComment?.second
        else -> null
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')

    private companion object {
        const val WORKSPACE = "/workspace"
        const val WORKSPACE_PREFIX = "/workspace/"
        const val CENTURY = 100
    }
}
