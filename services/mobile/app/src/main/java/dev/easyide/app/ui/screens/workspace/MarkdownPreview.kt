package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.easyide.app.ui.theme.EasyIdeFonts
import dev.easyide.app.ui.theme.EditorColors
import dev.easyide.app.ui.theme.editorColors

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

/**
 * Minimal markdown renderer: headings, fenced code, bullets, and inline bold /
 * italic / code / links.
 *
 * Line-based rather than a real parser - enough to read a README without
 * pulling in a markdown library, and it degrades to plain text on anything it
 * does not recognise. Rendered through a LazyColumn so a long document only
 * lays out what is on screen.
 */
@Composable
fun MarkdownPreview(source: String, modifier: Modifier = Modifier) {
    val colors = editorColors
    val blocks = remember(source, colors) { parseMarkdown(source, colors) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        items(blocks) { block -> MarkdownBlockView(block) }
    }
}

@Composable
internal fun MarkdownBlockView(block: MarkdownBlock) {
    val colors = editorColors

    when (block) {
        is MarkdownBlock.Heading -> Text(
            text = block.text,
            style = headingStyle(block.level),
            color = colors.plainText,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        )

        is MarkdownBlock.Paragraph -> Text(
            text = block.text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.plainText,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        is MarkdownBlock.Bullet -> BulletRow(BULLET, block.text)

        is MarkdownBlock.Numbered -> BulletRow(block.marker, block.text)

        is MarkdownBlock.Quote -> Row(modifier = Modifier.padding(vertical = 4.dp)) {
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .width(QUOTE_BAR_DP.dp)
                    .height(QUOTE_BAR_HEIGHT_DP.dp)
                    .background(colors.syntax.keyword),
            )
            Text(
                text = block.text,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.gutterText,
            )
        }

        is MarkdownBlock.Code -> if (block.language?.lowercase() == MERMAID_LANGUAGE) {
            MermaidView(
                source = block.lines.joinToString("\n"),
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .background(colors.panel),
        ) {
            // Naming the language matters for fences this renderer cannot draw
            // (mermaid, plantuml): the user sees the source labelled, rather
            // than wondering why a diagram silently became text.
            block.language?.takeIf { it.isNotBlank() }?.let { language ->
                Text(
                    text = if (language.lowercase() in DIAGRAM_LANGUAGES) {
                        "$language (diagram source - not rendered)"
                    } else {
                        language
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.gutterText,
                    modifier = Modifier.padding(start = 10.dp, top = 6.dp),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                Text(
                    text = block.lines.joinToString("\n"),
                    style = codeTextStyle().copy(color = colors.plainText),
                )
            }
        }

        // A rule is a hairline, not a slab: height must be constrained, and the
        // padding has to sit outside the background or it is painted too.
        MarkdownBlock.Rule -> Box(modifier = Modifier.padding(vertical = 12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RULE_HEIGHT_DP.dp)
                    .background(colors.panelBorder),
            )
        }
    }
}

@Composable
private fun BulletRow(marker: String, text: AnnotatedString) {
    val colors = editorColors
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.gutterText,
            modifier = Modifier.padding(start = 6.dp, end = 8.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = colors.plainText)
    }
}

@Composable
private fun headingStyle(level: Int) = when (level) {
    1 -> MaterialTheme.typography.headlineSmall
    2 -> MaterialTheme.typography.titleLarge
    3 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
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
private const val BULLET = "•"
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
private const val MERMAID_LANGUAGE = "mermaid"

/** Diagram languages we can label but not draw; mermaid is handled separately. */
private val DIAGRAM_LANGUAGES = setOf("plantuml", "dot", "graphviz")
private const val RULE_HEIGHT_DP = 1
private const val QUOTE_BAR_DP = 3
private const val QUOTE_BAR_HEIGHT_DP = 20
