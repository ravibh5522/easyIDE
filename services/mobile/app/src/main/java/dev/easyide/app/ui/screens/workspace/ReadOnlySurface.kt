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
import androidx.compose.ui.unit.dp
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
    val gutterWidth = remember(lines.size) {
        // Widen the gutter for files with many lines so numbers never clip.
        (GUTTER_WIDTH_DP + (lines.size.toString().length - 2).coerceAtLeast(0) * GUTTER_DIGIT_DP).dp
    }

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

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(lines) { index, line ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${index + 1}",
                    style = codeTextStyle(languageId).copy(color = colors.gutterText),
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .width(gutterWidth)
                        .background(colors.gutter)
                        .padding(end = 8.dp),
                )
                Text(
                    text = line,
                    style = codeTextStyle(languageId).copy(color = colors.plainText),
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(horizontalScroll)
                        .padding(start = 8.dp),
                )
            }
        }
    }
}

private const val GUTTER_DIGIT_DP = 8

/** Lines kept above a revealed line so its context is visible. */
private const val REVEAL_CONTEXT_LINES = 5
