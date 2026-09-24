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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import dev.easyide.app.R
import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.cropCorners
import dev.easyide.app.ui.theme.Accent
import dev.easyide.app.ui.theme.EditorColors
import dev.easyide.app.ui.theme.SyntaxRole
import dev.easyide.app.ui.theme.ThemeMode
import dev.easyide.app.ui.theme.themeTokensFor
import dev.easyide.app.ui.theme.toEditorColors

/** A contributed theme as the picker shows it; [ref] is `<extensionId>/<themeId>` when it has an id. */
data class ContributedThemeCard(val label: String, val ref: String?, val colors: EditorColors)

/** Writes behind the picker: a card sets `appearance.themeMode` or `workbench.colorTheme` (customization.md 8.1). */
interface ThemeActions {
    fun selectBuiltInTheme(mode: ThemeMode)
    fun selectContributedTheme(label: String)
    fun resetTheme()
}

/**
 * The theme setting as a grid of live mini-editor cards instead of a list of names: each card is
 * painted from the tokens that mode would apply, so the choice is made by looking, and nothing is
 * applied until a card is tapped. Themes from extensions follow the built-in modes; one selected
 * there wins over the built-in mode until a built-in card is tapped.
 */
@Composable
fun ThemePickerRow(snapshot: SettingsSnapshot, contributed: List<ContributedThemeCard>, actions: ThemeActions) {
    val setting = SettingsSchema.themeMode
    val selection = snapshot[SettingsSchema.colorTheme]
    val activeCard = contributed.firstOrNull { it.label == selection }
        ?: contributed.firstOrNull { it.ref != null && it.ref == selection }
    val modified = snapshot.isSetIn(setting, LayerId.USER) || snapshot.isSetIn(SettingsSchema.colorTheme, LayerId.USER)

    KitRow(
        title = setting.title.resolve(),
        subtitle = setting.description.resolve(),
        leading = { ModifiedDot(modified) },
        trailing = { if (modified) KitIconButton(Icons.Filled.Restore, stringResource(R.string.setting_reset), actions::resetTheme) },
        id = "setting:${setting.key}",
    )
    Column(Modifier.padding(start = Kit.space.l, end = Kit.space.l, bottom = Kit.space.m)) {
        ThemeCardGrid(snapshot[setting].takeIf { activeCard == null }, actions::selectBuiltInTheme) { stringResource(setting.label(it)) }
        if (contributed.isNotEmpty()) {
            BasicText(
                stringResource(R.string.theme_picker_extension_themes),
                Modifier.padding(top = Kit.space.l),
                style = Kit.type.labelMedium.copy(color = Kit.colors.textMuted),
            )
            FlowRow(
                Modifier.padding(top = Kit.space.s).fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(Kit.space.m),
                verticalArrangement = Arrangement.spacedBy(Kit.space.m),
            ) {
                contributed.forEach { card ->
                    ThemeCard(card.colors, card.label, card == activeCard) { actions.selectContributedTheme(card.label) }
                }
            }
        }
    }
}

@Composable
private fun ThemeCardGrid(selected: ThemeMode?, onSelect: (ThemeMode) -> Unit, label: @Composable (ThemeMode) -> String) {
    val systemInDark = isSystemInDarkTheme()
    val context = LocalContext.current
    // Preview the wallpaper accent too, where the platform has one, so the Dynamic card shows what Dynamic will do.
    val dynamicAccent = remember(systemInDark, context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@remember null
        val scheme = if (systemInDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        Accent(scheme.primary, scheme.onPrimary)
    }
    val previews = remember(systemInDark, dynamicAccent) {
        ThemeMode.entries.associateWith { themeTokensFor(it, systemInDark, dynamicAccent).toEditorColors() }
    }
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Kit.space.m),
        verticalArrangement = Arrangement.spacedBy(Kit.space.m),
    ) {
        ThemeMode.entries.forEach { mode -> ThemeCard(previews.getValue(mode), label(mode), mode == selected) { onSelect(mode) } }
    }
}

@Composable
private fun ThemeCard(colors: EditorColors, label: String, selected: Boolean, onClick: () -> Unit) {
    val chrome = Kit.colors
    val shape = RoundedCornerShape(Kit.radius.m)
    Column(
        Modifier
            .width(SettingsMetrics.themeCard)
            .clip(shape)
            .border(if (selected) Kit.marker else Kit.hairline, if (selected) chrome.accent else chrome.panelBorder, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .cropCorners(enabled = selected),
    ) {
        MiniEditor(colors)
        BasicText(
            label,
            Modifier.padding(horizontal = Kit.space.s, vertical = Kit.space.s),
            style = Kit.type.labelMedium.copy(color = if (selected) chrome.accent else chrome.plainText),
        )
    }
}

/** A tab strip with the active tab's accent bar over three lines of highlighted code. */
@Composable
private fun MiniEditor(colors: EditorColors) {
    val bar = Kit.marker
    val code = remember(colors) { sampleCode(colors) }
    Column(Modifier.fillMaxWidth().background(colors.background)) {
        Row(Modifier.fillMaxWidth().height(Kit.space.l).background(colors.tabInactive)) {
            Box(
                Modifier
                    .width(Kit.control.panelWidth / TAB_WIDTH_DIVISOR)
                    .height(Kit.space.l)
                    .background(colors.tabActive)
                    .drawBehind { drawRect(colors.tabActiveBorder, size = Size(size.width, bar.toPx())) },
            )
        }
        code.forEachIndexed { index, line ->
            val current = index == CURRENT_LINE
            BasicText(
                line,
                Modifier
                    .fillMaxWidth()
                    .background(if (current) colors.currentLine else colors.background)
                    .drawBehind {
                        if (!current) return@drawBehind
                        val x = size.width * CARET_POSITION
                        drawLine(colors.cursor, Offset(x, 0f), Offset(x, size.height), bar.toPx())
                    }
                    .padding(horizontal = Kit.space.s),
                style = monoStyle(Kit.type.labelSmall).copy(color = colors.plainText),
                maxLines = 1,
            )
        }
        Box(Modifier.height(Kit.space.xs))
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

private const val CURRENT_LINE = 1
private const val CARET_POSITION = 0.72f

/** The card's tab is a fifth of a panel wide, close to how the real tab strip reads. */
private const val TAB_WIDTH_DIVISOR = 5
