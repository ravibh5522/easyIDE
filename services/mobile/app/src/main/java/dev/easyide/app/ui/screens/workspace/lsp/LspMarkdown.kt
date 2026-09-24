package dev.easyide.app.ui.screens.workspace.lsp

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import dev.easyide.app.ui.screens.workspace.MarkdownBlock
import dev.easyide.app.ui.screens.workspace.MarkdownBlockView
import dev.easyide.app.ui.screens.workspace.codeTextStyle
import dev.easyide.app.ui.screens.workspace.parseMarkdown
import dev.easyide.app.ui.screens.workspace.syntax.TextMateHighlighter
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Server markdown made fit for the preview renderer: servers escape punctuation
 * (`\_`, pyright's `\(`), which the line-based renderer would show literally. Escapes of `*`
 * and backtick stay: unescaped, the renderer would read them as emphasis or code. Escapes
 * inside fenced code are code and stay too.
 */
internal object LspMarkdown {
    private val ESCAPE = Regex("""\\([\\_{}\[\]()#+\-.!|<>~])""")
    private const val FENCE = "```"

    fun prepare(markdown: String): String {
        var inFence = false
        return markdown.lines().joinToString("\n") { line ->
            val fence = line.trimStart().startsWith(FENCE)
            val out = if (inFence || fence) line else ESCAPE.replace(line) { it.groupValues[1] }
            if (fence) inFence = !inFence
            out
        }
    }
}

/**
 * Compact markdown for hover cards, completion docs and signature help. Code fences in the
 * document's own language are coloured with the TextMate grammar of [fileName], the
 * highlighter that colours the editor (lsp-features.md 4.3).
 */
@Composable
internal fun LspMarkdownView(markdown: String, languageId: String?, fileName: String?, modifier: Modifier = Modifier) {
    val colors = editorColors
    val blocks = remember(markdown, colors) { parseMarkdown(LspMarkdown.prepare(markdown), colors) }
    Column(modifier = modifier) {
        for (block in blocks) {
            if (block is MarkdownBlock.Code && fileName != null && block.language?.equals(languageId, ignoreCase = true) == true) {
                HighlightedCode(block.lines.joinToString("\n"), fileName)
            } else {
                MarkdownBlockView(block)
            }
        }
    }
}

@Composable
private fun HighlightedCode(source: String, fileName: String) {
    val colors = editorColors
    val styled by produceState(AnnotatedString(source), source, fileName, colors) {
        value = withContext(Dispatchers.Default) {
            TextMateHighlighter.highlight(HOVER_HIGHLIGHT_KEY, source, fileName, colors.syntax, 0, source.lines().size)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .background(colors.raised)
            .horizontalScroll(rememberScrollState())
            .padding(Spacing.s),
    ) {
        Text(text = styled, style = codeTextStyle().copy(color = colors.plainText))
    }
}

/** One highlighter slot shared by every popup: popups show one snippet at a time. */
private const val HOVER_HIGHLIGHT_KEY = "lsp-popup"
