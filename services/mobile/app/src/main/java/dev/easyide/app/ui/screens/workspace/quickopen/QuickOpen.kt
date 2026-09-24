package dev.easyide.app.ui.screens.workspace.quickopen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import dev.easyide.app.R
import dev.easyide.app.ui.commands.PickerOverlay
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.screens.workspace.files.FileIcon
import dev.easyide.app.ui.screens.workspace.files.FileIndex
import dev.easyide.app.ui.screens.workspace.files.FileIndexer
import dev.easyide.app.ui.screens.workspace.files.PathHit
import dev.easyide.app.ui.screens.workspace.files.PathMatcher
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Go to file (Ctrl+P): a fuzzy finder over the project's files, recent ones first.
 *
 * The index is built off the main thread by the workspace and arrives in [index]; ranking runs
 * off the main thread too, so typing stays smooth in a project with tens of thousands of files.
 * A leading `>` hands the rest to the command palette, and `@` / `#` to the symbol pickers
 * through [onPrefix], the same prefixes the palette understands.
 */
@Composable
fun QuickOpen(
    index: FileIndex,
    indexing: Boolean,
    recent: List<String>,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
    onCommands: (query: String) -> Unit,
    onPrefix: (prefix: Char, query: String) -> Boolean,
) {
    val colors = editorColors
    var field by remember { mutableStateOf(TextFieldValue()) }
    val hits by produceState(emptyList<PathHit>(), field.text, index, recent) {
        value = withContext(Dispatchers.Default) { PathMatcher.rank(field.text, index.paths, recent, MAX_RESULTS) }
    }

    PickerOverlay(
        value = field,
        onValueChange = { next ->
            val text = next.text
            when {
                text.startsWith(COMMANDS_PREFIX) -> { onDismiss(); onCommands(text.substring(1)) }
                text.isNotEmpty() && onPrefix(text[0], text.substring(1)) -> onDismiss()
                else -> field = next
            }
        },
        hint = stringResource(R.string.quick_open_hint),
        items = hits,
        itemKey = { it.path },
        onChoose = { onDismiss(); onOpen(it.path) },
        onDismiss = onDismiss,
        banner = {
            val note = when {
                hits.isEmpty() && indexing -> stringResource(R.string.quick_open_indexing)
                hits.isEmpty() -> stringResource(R.string.quick_open_empty)
                index.truncated -> stringResource(R.string.quick_open_truncated, FileIndexer.MAX_FILES)
                else -> null
            }
            note?.let {
                Text(
                    text = it,
                    style = Kit.text.caption,
                    color = colors.gutterText,
                    modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
                )
            }
        },
    ) { hit, _ ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            FileIcon(hit.fileName)
            Column(modifier = Modifier.weight(1f).padding(start = Spacing.m)) {
                Text(
                    text = highlighted(hit.fileName, hit.matched, hit.path.length - hit.fileName.length, colors.accent),
                    style = Kit.text.body,
                    color = colors.plainText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hit.directory.isNotEmpty()) {
                    Text(
                        text = hit.directory,
                        style = Kit.text.label,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** [name] with the characters at [matched] (offsets into the full path, [nameStart] of them before the name) in accent bold. */
private fun highlighted(name: String, matched: IntArray, nameStart: Int, accent: androidx.compose.ui.graphics.Color): AnnotatedString {
    val marks = matched.mapTo(HashSet()) { it - nameStart }
    return buildAnnotatedString {
        name.forEachIndexed { i, c ->
            if (i in marks) withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(c) } else append(c)
        }
    }
}

private const val MAX_RESULTS = 100
private const val COMMANDS_PREFIX = '>'
