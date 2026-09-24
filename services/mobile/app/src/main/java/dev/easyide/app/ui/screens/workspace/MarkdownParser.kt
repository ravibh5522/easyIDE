package dev.easyide.app.ui.screens.workspace

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.easyide.app.ui.theme.EasyIdeFonts
import dev.easyide.app.ui.theme.EditorColors

/** One rendered markdown block. */
internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: AnnotatedString) : MarkdownBlock
    data class Paragraph(val text: AnnotatedString) : MarkdownBlock
    data class Bullet(val text: AnnotatedString) : MarkdownBlock
    data class Numbered(val marker: String, val text: AnnotatedString) : MarkdownBlock
    data class Quote(val text: AnnotatedString) : MarkdownBlock
    /** [language] is the fence info string, e.g. "kotlin" or "mermaid". */
    data class Code(val language: String?, val lines: List<String>) : MarkdownBlock
    data object Rule : MarkdownBlock
}

internal fun parseMarkdown(source: String, colors: EditorColors): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    var codeLines: MutableList<String>? = null
    var codeLanguage: String? = null

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        blocks += MarkdownBlock.Paragraph(inline(paragraph.joinToString(" "), colors))
        paragraph.clear()
    }

    source.lines().forEach { raw ->
        val line = raw.trimEnd()

        if (line.trimStart().startsWith(CODE_FENCE)) {
            if (codeLines == null) {
                flushParagraph()
                codeLanguage = line.trimStart().removePrefix(CODE_FENCE).trim().takeIf { it.isNotEmpty() }
                codeLines = mutableListOf()
            } else {
                blocks += MarkdownBlock.Code(codeLanguage, codeLines.orEmpty())
                codeLines = null
                codeLanguage = null
            }
            return@forEach
        }
        codeLines?.let { it += raw; return@forEach }

        when {
            line.isBlank() -> flushParagraph()

            line.startsWith("#") -> {
                flushParagraph()
                val level = line.takeWhile { it == '#' }.length
                blocks += MarkdownBlock.Heading(level, inline(line.drop(level).trim(), colors))
            }

            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Bullet(inline(line.trimStart().drop(2), colors))
            }

            line.trimStart().startsWith("> ") -> {
                flushParagraph()
                blocks += MarkdownBlock.Quote(inline(line.trimStart().drop(2), colors))
            }

            ORDERED_ITEM.matches(line.trimStart()) -> {
                flushParagraph()
                val marker = ORDERED_ITEM.find(line.trimStart())?.groupValues?.get(1).orEmpty()
                blocks += MarkdownBlock.Numbered(marker, inline(line.trimStart().removePrefix(marker).trim(), colors))
            }

            line.all { it == '-' } && line.length >= RULE_MIN_LENGTH -> {
                flushParagraph()
                blocks += MarkdownBlock.Rule
            }

            else -> paragraph += line.trim()
        }
    }
    codeLines?.let { blocks += MarkdownBlock.Code(codeLanguage, it) }
    flushParagraph()
    return blocks
}

/**
 * Inline formatting: emphasis, code spans and links.
 *
 * Builds the output string as it scans rather than annotating the source in
 * place. Annotating in place was simpler but left the markers visible - the
 * preview showed literal `**bold**` and `[label](url)` - which is exactly what
 * a preview is supposed to remove.
 */
private fun inline(text: String, colors: EditorColors): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var index = 0

    while (index < text.length) {
        val match = INLINE_PATTERNS
            .mapNotNull { (regex, kind) -> regex.find(text, index)?.let { it to kind } }
            .minByOrNull { it.first.range.first }

        if (match == null) {
            builder.append(text.substring(index))
            break
        }

        val (found, kind) = match
        if (found.range.first > index) {
            builder.append(text.substring(index, found.range.first))
        }

        when (kind) {
            InlineKind.CODE -> builder.appendStyled(
                found.value.trim('`'),
                SpanStyle(fontFamily = EasyIdeFonts.mono, color = colors.syntax.string),
            )

            InlineKind.BOLD -> builder.appendStyled(
                found.value.removeSurrounding("**"),
                SpanStyle(fontWeight = FontWeight.Bold),
            )

            InlineKind.ITALIC -> builder.appendStyled(
                found.value.removeSurrounding("*"),
                SpanStyle(fontStyle = FontStyle.Italic),
            )

            InlineKind.LINK -> {
                val label = found.value.substringAfter('[').substringBefore(']')
                val url = found.value.substringAfterLast('(').substringBeforeLast(')')
                // LinkAnnotation makes the span genuinely tappable - plain
                // styling only looked like a link.
                builder.withLink(
                    LinkAnnotation.Url(
                        url = url,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = colors.syntax.keyword,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                    ),
                ) { append(label) }
            }
        }
        index = found.range.last + 1
    }

    return builder.toAnnotatedString()
}

private fun AnnotatedString.Builder.appendStyled(text: String, style: SpanStyle) {
    val start = length
    append(text)
    addStyle(style, start, length)
}

private enum class InlineKind { CODE, BOLD, ITALIC, LINK }

private const val CODE_FENCE = "```"
private const val RULE_MIN_LENGTH = 3
private val CODE_SPAN = Regex("`[^`\\n]+`")
private val BOLD = Regex("""\*\*[^*\n]+\*\*""")
private val ITALIC = Regex("""(?<!\*)\*[^*\n]+\*(?!\*)""")

/** Order matters: code wins over emphasis, and bold must beat italic. */
private val INLINE_PATTERNS by lazy {
    listOf(
        CODE_SPAN to InlineKind.CODE,
        LINK to InlineKind.LINK,
        BOLD to InlineKind.BOLD,
        ITALIC to InlineKind.ITALIC,
    )
}
private val LINK = Regex("""\[[^\]\n]+]\([^)\n]+\)""")
private val ORDERED_ITEM = Regex("""^(\d+\.)\s+.*""")
