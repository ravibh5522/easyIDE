package dev.easyide.app.ui.screens.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import dev.easyide.app.data.settings.EditorFont
import dev.easyide.app.data.settings.EditorOptions
import dev.easyide.app.data.settings.EditorSettingsSchema
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.ui.foundation.LocalSettings
import dev.easyide.app.ui.screens.workspace.syntax.LanguageConfigs
import dev.easyide.app.ui.theme.EasyIdeFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The `editor.*` options for a document in [languageId], from the settings snapshot in composition. */
@Composable
internal fun rememberEditorOptions(languageId: String?): EditorOptions =
    EditorSettingsSchema.options(LocalSettings.current, languageId)

private fun EditorFont.family(): FontFamily = when (this) {
    EditorFont.MONOSPACE -> FontFamily.Monospace
    EditorFont.SANS -> FontFamily.SansSerif
    EditorFont.GEIST_MONO -> EasyIdeFonts.mono
}

/** VS Code ships ligatures off; a monospace face that has them would otherwise fuse `=>` and `!=`. */
private const val NO_LIGATURES = "liga 0, calt 0"

/**
 * One definition so the gutter, the buffer, the caret and the popups share a font and a line
 * height and never drift. The line height derives from the font size ([EditorOptions.lineHeightFor]),
 * so a pinch, a changed setting or the system font scale moves both together.
 * [languageId] applies `[lang]` settings blocks for the open document; [fontSizeOverride] is the
 * live size while a pinch is in progress.
 */
@Composable
fun codeTextStyle(languageId: String? = null, fontSizeOverride: Int? = null): TextStyle {
    val settings = LocalSettings.current
    val fontSize = fontSizeOverride ?: settings.get(SettingsSchema.editorFontSize, languageId)
    return codeTextStyleOf(EditorSettingsSchema.options(settings, languageId), fontSize)
}

internal fun codeTextStyleOf(options: EditorOptions, fontSize: Int): TextStyle = TextStyle(
    fontFamily = options.fontFamily.family(),
    fontSize = fontSize.sp,
    lineHeight = options.lineHeightFor(fontSize).sp,
    letterSpacing = options.letterSpacing.sp,
    fontFeatureSettings = NO_LIGATURES,
    // Centre the glyphs in the line box and keep the platform's font padding out of it, so the
    // pitch is exactly the line height and the caret and squiggles sit where VS Code puts them.
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** The document's language id, looked up off the main thread (the grammar index may still be loading). */
@Composable
internal fun rememberLanguageId(fileName: String): String? {
    val id by produceState<String?>(null, fileName) {
        value = withContext(Dispatchers.IO) { LanguageConfigs.languageIdFor(fileName) }
    }
    return id
}
