package dev.easyide.app.ui.screens.diagnostics

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import dev.easyide.app.ui.screens.settings.contentWidth
import dev.easyide.app.ui.theme.EasyIdeFonts
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.app.ui.theme.sectionHeader

/** Share of a row's width taken by its label; the value gets the rest. */
private const val LABEL_WEIGHT = 0.4f
private const val VALUE_WEIGHT = 0.6f

/** Bytes as the system formats them ("12 MB"), using the device's units and locale. */
@Composable
internal fun bytesText(bytes: Long): String {
    val context = LocalContext.current
    return remember(bytes) { Formatter.formatShortFileSize(context, bytes) }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.sectionHeader,
        color = editorColors.textMuted,
        modifier = Modifier
            .contentWidth()
            .padding(start = Spacing.l, end = Spacing.l, top = Spacing.xl, bottom = Spacing.s)
            .semantics { heading() },
    )
}

@Composable
internal fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.contentWidth().padding(horizontal = Spacing.l)) {
        Column(
            modifier = Modifier.padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
            content = content,
        )
    }
}

/** A label and its value, read out as one item by screen readers. */
@Composable
internal fun InfoRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = editorColors.textMuted,
            modifier = Modifier.weight(LABEL_WEIGHT),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(VALUE_WEIGHT),
        )
    }
}

/** Log and report text: monospaced and dense, so columns and stack frames line up. */
@Composable
internal fun monoStyle(): TextStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = EasyIdeFonts.mono)
