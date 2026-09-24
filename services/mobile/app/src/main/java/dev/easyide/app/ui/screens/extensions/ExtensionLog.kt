package dev.easyide.app.ui.screens.extensions

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.extensions.TimedLogEntry
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.Tone
import dev.easyide.extensions.action.LogLevel
import java.text.DateFormat
import java.util.Date

/** The ring holds more than a screen can usefully show; the newest lines are the ones read. */
internal const val LOG_LINES_SHOWN = 100

/** One log line as text: time, level, the extension unless the log is already about one, message. */
internal fun logLine(e: TimedLogEntry, time: String, showId: Boolean): String {
    val who = if (showId) e.entry.extensionId?.value?.let { "[$it] " }.orEmpty() else ""
    return "$time ${e.entry.level.name} $who${e.entry.message}"
}

/** The lines the section shows, as one text for the clipboard. */
internal fun logText(entries: List<TimedLogEntry>, showId: Boolean, timeOf: (Long) -> String): String =
    entries.take(LOG_LINES_SHOWN).joinToString("\n") { logLine(it, timeOf(it.atMs), showId) }

/** Errors and warnings carry their signal colour; everything else stays in text tone. */
internal fun levelTone(level: LogLevel): Tone = when (level) {
    LogLevel.ERROR -> Tone.Danger
    LogLevel.WARN -> Tone.Warning
    else -> Tone.Neutral
}

/**
 * The Extension Log (ECO-31): [entries] newest first, with Copy and, where the log is the whole
 * ring, Clear. [showId] names the extension on each line; a page's own log leaves it out. [flat] is
 * the side-panel form.
 */
@Composable
internal fun LogSection(entries: List<TimedLogEntry>, showId: Boolean, flat: Boolean, onClear: (() -> Unit)?) {
    val format = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    val timeOf: (Long) -> String = { format.format(Date(it)) }
    val context = LocalContext.current
    val title = stringResource(R.string.ext_log_title)
    KitSection(title, count = entries.size.takeIf { it > 0 }, flat = flat, collapsible = true) {
        if (entries.isEmpty()) {
            KitEmptyState(EmptyArt.Prompt, stringResource(R.string.ext_log_empty))
            return@KitSection
        }
        Column(Modifier.padding(Kit.control.hPad)) {
            entries.take(LOG_LINES_SHOWN).forEach { e ->
                val color = if (levelTone(e.entry.level) == Tone.Neutral) Kit.colors.plainText else levelTone(e.entry.level).content(Kit.colors)
                BasicText(logLine(e, timeOf(e.atMs), showId), style = Kit.text.monoSmall.copy(color = color))
            }
        }
        Row(Modifier.padding(horizontal = Kit.control.hPad), horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
            KitButton(stringResource(R.string.extui_log_copy), {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText(title, logText(entries, showId, timeOf)))
            }, style = KitButtonStyle.Ghost)
            if (onClear != null) KitButton(stringResource(R.string.ext_log_clear), onClear, style = KitButtonStyle.Ghost)
        }
    }
}
