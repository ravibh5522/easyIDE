package dev.easyide.app.ui.screens.diagnostics

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.components.MonoText
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.Tone

/** Widest a value grows beside its label before it wraps; the label keeps the rest of the row. */
private val VALUE_MAX_WIDTH = 240.dp

/** Values that wrap (paths, kernel strings) show at most this many lines. */
private const val VALUE_MAX_LINES = 3

/** Bytes as the system formats them ("12 MB"), using the device's units and locale. */
@Composable
internal fun bytesText(bytes: Long): String {
    val context = LocalContext.current
    return remember(bytes) { Formatter.formatShortFileSize(context, bytes) }
}

/** A label and its measured value in mono at the right, read out as one item by screen readers. */
@Composable
internal fun InfoRow(label: String, value: String, tone: Tone? = null) {
    KitRow(
        title = label,
        trailing = { MonoText(value, Modifier.widthIn(max = VALUE_MAX_WIDTH), muted = false, tone = tone, maxLines = VALUE_MAX_LINES) },
    )
}

/** Buttons under a group's rows: ghost actions on the object above them. */
@Composable
internal fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.padding(horizontal = Kit.space.s, vertical = Kit.space.xs), horizontalArrangement = Arrangement.spacedBy(Kit.space.xs), content = content)
}
