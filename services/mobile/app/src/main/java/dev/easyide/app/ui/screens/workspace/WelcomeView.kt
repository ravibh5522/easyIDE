package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.app.ui.commands.CommandRegistry
import dev.easyide.app.ui.commands.Keymap
import dev.easyide.app.ui.screens.workspace.files.FileIcon
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors

/**
 * What the editor area shows with no tab open: the brand mark as a quiet watermark, the files
 * opened recently in this project, and a cheat sheet of the keyboard shortcuts.
 *
 * The cheat sheet is generated from the live [Keymap] and [CommandRegistry] - the chord shown
 * is whatever wins dispatch right now, user rebinds and extension bindings included - and a
 * command with no binding is left out rather than shown blank. [SHORTCUT_COMMANDS] only picks
 * *which* commands are worth advertising, and in what order.
 */
@Composable
fun WelcomeView(
    recent: List<String>,
    registry: CommandRegistry,
    keymap: Keymap,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = editorColors
    val shortcuts = SHORTCUT_COMMANDS.mapNotNull { id ->
        val command = registry[id] ?: return@mapNotNull null
        val chord = keymap.labelFor(id) ?: return@mapNotNull null
        command to chord
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(colors.background)) {
        val wide = maxWidth >= WIDE_LAYOUT
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Watermark()
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textMuted,
                modifier = Modifier.padding(top = Spacing.m),
            )
            Text(
                text = stringResource(R.string.welcome_open_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textDisabled,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.xl),
            )
            val recentList = @Composable { RecentList(recent, onOpenFile, Modifier.widthIn(max = COLUMN_MAX_WIDTH)) }
            val shortcutList = @Composable {
                Shortcuts(shortcuts.map { (command, chord) -> command.title.text() to chord }, Modifier.widthIn(max = COLUMN_MAX_WIDTH))
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxl), verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) { recentList() }
                    Column(modifier = Modifier.weight(1f)) { shortcutList() }
                }
            } else {
                recentList()
                Column(modifier = Modifier.padding(top = Spacing.xl)) { shortcutList() }
            }
        }
    }
}

@Composable
private fun RecentList(recent: List<String>, onOpenFile: (String) -> Unit, modifier: Modifier) {
    val colors = editorColors
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.welcome_recent))
        if (recent.isEmpty()) {
            Text(
                text = stringResource(R.string.welcome_no_recent),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textDisabled,
            )
        }
        recent.take(RECENT_SHOWN).forEach { path ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenFile(path) }
                    .minimumInteractiveComponentSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FileIcon(path.substringAfterLast('/'))
                Column(modifier = Modifier.padding(start = Spacing.m)) {
                    Text(
                        text = path.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.plainText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val directory = path.substringBeforeLast('/', "")
                    if (directory.isNotEmpty()) {
                        Text(
                            text = directory,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Shortcuts(rows: List<Pair<String, String>>, modifier: Modifier) {
    val colors = editorColors
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.welcome_shortcuts))
        rows.forEach { (title, chord) ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.weight(1f),
                )
                Text(text = chord, style = MaterialTheme.typography.labelMedium, color = colors.plainText)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = editorColors.textMuted,
        modifier = Modifier.padding(bottom = Spacing.s),
    )
}

/**
 * The brand glyph (`</`, as in the launcher icon) drawn from theme tokens at low contrast, so
 * it reads as a watermark in every palette rather than as an image with colours of its own.
 */
@Composable
private fun Watermark() {
    val color = editorColors.panelBorder
    Canvas(modifier = Modifier.size(WATERMARK_SIZE)) {
        val unit = size.width / GLYPH_VIEWPORT
        val stroke = Stroke(width = GLYPH_STROKE * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(
            Path().apply { moveTo(30f * unit, 40f * unit); lineTo(18f * unit, 54f * unit); lineTo(30f * unit, 68f * unit) },
            color,
            style = stroke,
        )
        drawPath(Path().apply { moveTo(50f * unit, 72f * unit); lineTo(64f * unit, 36f * unit) }, color, style = stroke)
    }
}

/** The commands the cheat sheet advertises, in order; their chords are read from the keymap, never written here. */
private val SHORTCUT_COMMANDS = listOf(
    CommandIds.QUICK_OPEN,
    CommandIds.SHOW_COMMANDS,
    CommandIds.FIND,
    CommandIds.REPLACE,
    CommandIds.GO_TO_LINE,
    CommandIds.UNDO,
    CommandIds.REDO,
    CommandIds.SAVE,
    CommandIds.TOGGLE_EXPLORER,
    CommandIds.TOGGLE_TERMINAL,
    CommandIds.TRIGGER_SUGGEST,
    CommandIds.REVEAL_DEFINITION,
)

private const val RECENT_SHOWN = 6
private const val GLYPH_VIEWPORT = 108f
private const val GLYPH_STROKE = 6f
private val WATERMARK_SIZE = 96.dp
private val WIDE_LAYOUT = 600.dp
private val COLUMN_MAX_WIDTH = 360.dp
