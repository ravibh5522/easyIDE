package dev.easyide.app.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.ui.theme.Accent
import dev.easyide.app.ui.theme.EasyIdeFonts
import dev.easyide.app.ui.theme.EditorColors
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.Stroke
import dev.easyide.app.ui.theme.SyntaxRole
import dev.easyide.app.ui.theme.ThemeMode
import dev.easyide.app.ui.theme.TypeScale
import dev.easyide.app.ui.theme.themeTokensFor
import dev.easyide.app.ui.theme.toEditorColors

/**
 * The theme setting as a grid of live mini-editor cards instead of a dropdown
 * of names: each card is painted from the tokens that mode would apply, so the
 * choice is made by looking, and nothing is applied until a card is tapped.
 */
@Composable
fun ThemePickerRow(
    snapshot: SettingsSnapshot,
    actions: SettingActions,
    modifier: Modifier = Modifier,
) {
    val setting = SettingsSchema.themeMode
    val current = snapshot[setting]

    Column(modifier = modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(setting.title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(setting.description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (snapshot.isModified(setting)) {
                IconButton(onClick = { actions.reset(setting) }) {
                    Icon(Icons.Filled.Restore, contentDescription = stringResource(R.string.setting_reset))
                }
            }
        }
        ThemeCardGrid(
            selected = current,
            onSelect = { actions.set(setting, it) },
            label = { stringResource(setting.label(it)) },
            modifier = Modifier.padding(top = Spacing.m),
        )
    }
}

@Composable
private fun ThemeCardGrid(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    label: @Composable (ThemeMode) -> String,
    modifier: Modifier = Modifier,
) {
    val systemInDark = isSystemInDarkTheme()
    val context = LocalContext.current
    // Preview the wallpaper accent too, where the platform has one, so the
    // Dynamic card shows what Dynamic will actually do.
    val dynamicAccent = remember(systemInDark, context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@remember null
        val scheme = if (systemInDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        Accent(scheme.primary, scheme.onPrimary)
    }
    val previews = remember(systemInDark, dynamicAccent) {
        ThemeMode.entries.associateWith { themeTokensFor(it, systemInDark, dynamicAccent).toEditorColors() }
    }

    FlowRow(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        ThemeMode.entries.forEach { mode ->
            ThemeCard(
                colors = previews.getValue(mode),
                label = label(mode),
                selected = mode == selected,
                onClick = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun ThemeCard(colors: EditorColors, label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val ringWidth = if (selected) Stroke.accentBar else Stroke.hairline

    Column(
        modifier = Modifier
            .width(CARD_WIDTH)
            .clip(shape)
            .border(ringWidth, ring, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        MiniEditor(colors)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.s),
        )
    }
}

/** A tab strip with the active tab's accent bar over three lines of highlighted code. */
@Composable
private fun MiniEditor(colors: EditorColors) {
    Column(modifier = Modifier.fillMaxWidth().background(colors.background)) {
        Row(modifier = Modifier.fillMaxWidth().height(Spacing.l).background(colors.tabInactive)) {
            Box(
                modifier = Modifier
                    .width(TAB_WIDTH)
                    .height(Spacing.l)
                    .background(colors.tabActive)
                    .drawBehind { drawRect(colors.tabActiveBorder, size = Size(size.width, Stroke.accentBar.toPx())) },
            )
        }
        val code = remember(colors) { sampleCode(colors) }
        code.forEachIndexed { index, line ->
            val current = index == CURRENT_LINE
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = EasyIdeFonts.mono,
                    fontSize = TypeScale.label,
                    color = colors.plainText,
                ),
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (current) colors.currentLine else colors.background)
                    .drawBehind {
                        if (!current) return@drawBehind
                        // The caret, after the text on the current line.
                        val x = size.width * CARET_POSITION
                        drawLine(colors.cursor, Offset(x, 0f), Offset(x, size.height), Stroke.accentBar.toPx())
                    }
                    .padding(horizontal = Spacing.s),
            )
        }
        Box(Modifier.height(Spacing.xs))
    }
}

/** The sample, as (text, role) runs per line: enough roles to judge a syntax palette at a glance. */
private val SAMPLE: List<List<Pair<String, SyntaxRole>>> = listOf(
    listOf("fun " to SyntaxRole.KEYWORD, "greet" to SyntaxRole.FUNCTION, "(n: " to SyntaxRole.PUNCTUATION,
        "Int" to SyntaxRole.TYPE, ") {" to SyntaxRole.PUNCTUATION),
    listOf("  val " to SyntaxRole.KEYWORD, "s = " to SyntaxRole.VARIABLE, "\"hi\"" to SyntaxRole.STRING),
    listOf("  " to SyntaxRole.PLAIN, "// ok " to SyntaxRole.COMMENT, "42" to SyntaxRole.NUMBER),
)

private fun sampleCode(colors: EditorColors): List<AnnotatedString> = SAMPLE.map { runs ->
    buildAnnotatedString {
        runs.forEach { (text, role) -> withStyle(SpanStyle(color = colors.syntax[role])) { append(text) } }
    }
}

private val CARD_WIDTH = 152.dp
private val TAB_WIDTH = 56.dp
private const val CURRENT_LINE = 1
private const val CARET_POSITION = 0.72f
