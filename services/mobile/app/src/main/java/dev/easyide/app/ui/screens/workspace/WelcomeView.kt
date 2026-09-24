package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.screens.workspace.files.FileIcon
import dev.easyide.app.ui.theme.EasyIdeFonts

/**
 * What the editor area shows with no tab open: one line of guidance with the "New file" command
 * (when the host passes [onNewFile]), the files opened recently in this project, and a cheat
 * sheet of the keyboard shortcuts. Content is capped at the readable width and centred.
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
    onNewFile: (() -> Unit)? = null,
) {
    val shortcuts = SHORTCUT_COMMANDS.mapNotNull { id ->
        val command = registry[id] ?: return@mapNotNull null
        val chord = keymap.labelFor(id) ?: return@mapNotNull null
        command.title.text() to chord
    }

    Box(modifier.fillMaxSize().background(Kit.colors.background).verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Kit.contentMax).fillMaxWidth()) {
            KitEmptyState(
                art = EmptyArt.Editor,
                message = stringResource(R.string.welcome_open_hint),
                action = onNewFile?.let { KitAction(stringResource(R.string.wp_new_file), it) },
            )
            KitSection(stringResource(R.string.welcome_recent)) {
                if (recent.isEmpty()) KitRow(stringResource(R.string.welcome_no_recent), enabled = false)
                recent.take(RECENT_SHOWN).forEach { path ->
                    KitRow(
                        title = path.substringAfterLast('/'),
                        subtitle = path.substringBeforeLast('/', "").ifEmpty { null },
                        mono = true,
                        leading = { FileIcon(path.substringAfterLast('/')) },
                        onClick = { onOpenFile(path) },
                    )
                }
            }
            if (shortcuts.isNotEmpty()) {
                KitSection(stringResource(R.string.welcome_shortcuts)) {
                    shortcuts.forEach { (title, chord) ->
                        KitRow(title, trailing = { BasicText(chord, style = Kit.type.labelMedium.copy(fontFamily = EasyIdeFonts.mono, color = Kit.colors.plainText)) })
                    }
                }
            }
            Spacer(Modifier.height(Kit.space.xxl))
        }
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
