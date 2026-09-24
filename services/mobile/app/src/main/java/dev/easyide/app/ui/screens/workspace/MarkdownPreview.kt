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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit

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
    val colors = Kit.colors
    val blocks = remember(source, colors) { parseMarkdown(source, colors) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = Kit.space.l, vertical = Kit.space.m),
    ) {
        items(blocks) { block -> MarkdownBlockView(block) }
    }
}

@Composable
internal fun MarkdownBlockView(block: MarkdownBlock) {
    val colors = Kit.colors
    val space = Kit.space

    when (block) {
        is MarkdownBlock.Heading -> BasicText(
            text = block.text,
            style = headingStyle(block.level).copy(color = colors.plainText),
            modifier = Modifier.padding(top = space.m, bottom = space.s),
        )

        is MarkdownBlock.Paragraph -> BasicText(
            text = block.text,
            style = Kit.text.body.copy(color = colors.plainText),
            modifier = Modifier.padding(vertical = space.xs),
        )

        is MarkdownBlock.Bullet -> BulletRow(BULLET, block.text)

        is MarkdownBlock.Numbered -> BulletRow(block.marker, block.text)

        is MarkdownBlock.Quote -> Row(modifier = Modifier.padding(vertical = space.xs)) {
            Box(
                modifier = Modifier
                    .padding(end = space.m)
                    .width(QUOTE_BAR_DP.dp)
                    .height(QUOTE_BAR_HEIGHT_DP.dp)
                    .background(colors.syntax.keyword),
            )
            BasicText(
                text = block.text,
                style = Kit.text.body.copy(color = colors.gutterText),
            )
        }

        is MarkdownBlock.Code -> if (block.language?.lowercase() == MERMAID_LANGUAGE) {
            MermaidView(
                source = block.lines.joinToString("\n"),
                modifier = Modifier.padding(vertical = space.s),
            )
        } else Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = space.s)
                .background(colors.panel),
        ) {
            // Naming the language matters for fences this renderer cannot draw
            // (mermaid, plantuml): the user sees the source labelled, rather
            // than wondering why a diagram silently became text.
            block.language?.takeIf { it.isNotBlank() }?.let { language ->
                BasicText(
                    text = if (language.lowercase() in DIAGRAM_LANGUAGES) {
                        stringResource(R.string.wp_markdown_diagram_source, language)
                    } else {
                        language
                    },
                    style = Kit.text.label.copy(color = colors.gutterText),
                    modifier = Modifier.padding(start = space.m, top = space.s),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(space.m),
            ) {
                BasicText(
                    text = block.lines.joinToString("\n"),
                    style = codeTextStyle().copy(color = colors.plainText),
                )
            }
        }

        // A rule is a hairline, not a slab: height must be constrained, and the
        // padding has to sit outside the background or it is painted too.
        MarkdownBlock.Rule -> Box(modifier = Modifier.padding(vertical = space.m)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Kit.hairline)
                    .background(colors.panelBorder),
            )
        }
    }
}

@Composable
private fun BulletRow(marker: String, text: AnnotatedString) {
    val colors = Kit.colors
    Row(modifier = Modifier.padding(vertical = Kit.space.xxs)) {
        BasicText(
            text = marker,
            style = Kit.text.body.copy(color = colors.gutterText),
            modifier = Modifier.padding(start = Kit.space.s, end = Kit.space.s),
        )
        BasicText(text = text, style = Kit.text.body.copy(color = colors.plainText))
    }
}

@Composable
private fun headingStyle(level: Int) = when (level) {
    1 -> Kit.text.display
    2 -> Kit.text.display
    3 -> Kit.text.heading
    else -> Kit.text.title
}

private const val BULLET = "•"
private const val MERMAID_LANGUAGE = "mermaid"

/** Diagram languages we can label but not draw; mermaid is handled separately. */
private val DIAGRAM_LANGUAGES = setOf("plantuml", "dot", "graphviz")
private const val QUOTE_BAR_DP = 3
private const val QUOTE_BAR_HEIGHT_DP = 20
