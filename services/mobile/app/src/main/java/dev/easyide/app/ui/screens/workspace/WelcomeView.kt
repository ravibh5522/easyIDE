package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.app.ui.commands.CommandRegistry
import dev.easyide.app.ui.commands.Keymap
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.PromptGlyph
import dev.easyide.app.ui.screens.workspace.files.FileIcon

/**
 * What the editor area shows with no tab open, in VS Code's start layout: a muted mark and the app's
 * name, then two columns (Start: the commands worth reaching first; Recent: the files opened lately in
 * this project), then the keyboard shortcuts as a compact table. Under a wide window the columns stack.
 * Content is capped at the readable width; there is no picture, so the page costs no more height
 * than its rows.
 *
 * Start and the shortcuts are generated from the live [Keymap] and [CommandRegistry] - the chord shown
 * is whatever wins dispatch right now, user rebinds and extension bindings included - and a command with
 * no binding is left out of the shortcuts rather than shown blank. [START_COMMANDS] and
 * [SHORTCUT_COMMANDS] only pick *which* commands are worth advertising, and in what order.
 */
@Composable
fun WelcomeView(
    recent: List<String>,
    registry: CommandRegistry,
    keymap: Keymap,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNewFile: (() -> Unit)? = null,
) {
    val shortcuts = SHORTCUT_COMMANDS.mapNotNull { id ->
        val command = registry[id] ?: return@mapNotNull null
        val chord = keymap.labelFor(id) ?: return@mapNotNull null
        command.title.text() to chord
    }
    val start = @Composable {
        KitSection(stringResource(R.string.welcome_start)) {
            onNewFile?.let { KitRow(stringResource(R.string.wp_new_file), onClick = it) }
            START_COMMANDS.forEach { id ->
                val command = registry[id] ?: return@forEach
                KitRow(command.title.text(), onClick = { registry.execute(id) }, trailing = keymap.labelFor(id)?.let { chord -> { Chord(chord) } })
            }
        }
    }
    val recentList = @Composable {
        KitSection(stringResource(R.string.welcome_recent)) {
            if (recent.isEmpty()) KitRow(stringResource(R.string.welcome_no_recent), enabled = false)
            recent.take(RECENT_SHOWN).forEach { path ->
                KitRow(
                    title = path.substringAfterLast('/'),
                    subtitle = path.substringBeforeLast('/', "").ifEmpty { null },
                    leading = { FileIcon(path.substringAfterLast('/'), size = Kit.control.rowIcon) },
                    onClick = { onOpenFile(path) },
                )
            }
        }
    }

    Box(modifier.fillMaxSize().background(Kit.colors.background).verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Kit.contentMax).fillMaxWidth().padding(vertical = Kit.space.l)) {
            Row(Modifier.padding(horizontal = Kit.control.hPad, vertical = Kit.space.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.m)) {
                PromptGlyph(color = Kit.colors.textDisabled, height = Kit.control.hitBox)
                BasicText(stringResource(R.string.app_name), style = Kit.text.display.copy(color = Kit.colors.textMuted), maxLines = 1)
            }
            if (Kit.metrics.width.isCompact) {
                start()
                recentList()
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Kit.space.l)) {
                    Column(Modifier.weight(1f)) { start() }
                    Column(Modifier.weight(1f)) { recentList() }
                }
            }
            if (shortcuts.isNotEmpty()) {
                KitSection(stringResource(R.string.welcome_shortcuts)) {
                    shortcuts.forEach { (title, chord) -> KitRow(title, trailing = { Chord(chord) }) }
                }
            }
        }
    }
}

@Composable
private fun Chord(chord: String) {
    BasicText(chord, style = Kit.text.monoSmall.copy(color = Kit.colors.textMuted), maxLines = 1)
}

/** The commands the Start column offers after New file, in order. */
private val START_COMMANDS = listOf(CommandIds.QUICK_OPEN, CommandIds.SHOW_COMMANDS)

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
