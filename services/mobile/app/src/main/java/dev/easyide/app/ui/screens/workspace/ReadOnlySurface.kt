package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import dev.easyide.app.data.settings.LineNumbers
import dev.easyide.app.ui.screens.workspace.decor.EditorGutter
import dev.easyide.app.ui.theme.editorColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Virtualised viewer: only the visible lines are ever measured. Read-only tabs are the
 * multi-megabyte files and environment files opened by navigation; a reveal request scrolls
 * its line into view (there is no caret to move).
 */
@Composable
internal fun ReadOnlySurface(tab: EditorTab, interaction: EditorInteraction?) {
    val colors = editorColors
    val languageId = rememberLanguageId(tab.name)
    val horizontalScroll = rememberScrollState()
    val listState = rememberLazyListState()
    // Splitting on the main thread was a visible stall on open. The result is tagged with its
    // source so a tab switch never shows the previous file's lines for a frame.
    val split by produceState<Pair<String, List<String>>?>(null, tab.content) {
        value = tab.content to withContext(Dispatchers.Default) { tab.content.lines() }
    }
    val lines = split?.takeIf { it.first === tab.content }?.second ?: return
    val options = rememberEditorOptions(languageId)
    val style = codeTextStyle(languageId)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charWidth = remember(style, density) { with(density) { measurer.measure("0", style).size.width.toDp() } }

    if (interaction != null) {
        val request by interaction.selectionRequests.collectAsState()
        val pending = request?.takeIf { it.path == tab.relativePath && it.text == tab.content }
        LaunchedEffect(pending?.id) {
            val r = pending ?: return@LaunchedEffect
            interaction.onSelectionRequestApplied(r)
            val line = tab.content.subSequence(0, r.start.coerceIn(0, tab.content.length)).count { it == '\n' }
            listState.scrollToItem((line - REVEAL_CONTEXT_LINES).coerceAtLeast(0))
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Widens with the line count, so numbers never clip.
        val gutterWidth = EditorGutter.width(lines.size, options.lineNumbers, charWidth, EditorGutter.isCompact(maxWidth))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(lines) { index, line ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (options.lineNumbers == LineNumbers.OFF) "" else "${index + 1}",
                        style = style.copy(color = colors.gutterText),
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(gutterWidth).background(colors.gutter).padding(end = charWidth),
                    )
                    Text(
                        text = line,
                        style = style.copy(color = colors.plainText),
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(horizontalScroll)
                    )
                }
            }
        }
    }
}

/** Lines kept above a revealed line so its context is visible. */
private const val REVEAL_CONTEXT_LINES = 5
