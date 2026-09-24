package dev.easyide.app.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.easyide.app.ui.props.IconStyle
import dev.easyide.app.ui.props.LocalFeel

/**
 * Shell and workspace names that mean one of the custom glyphs. `settings` and `terminal` need
 * no alias: they are glyph names already.
 */
private val CUSTOM_ALIASES = mapOf(
    "extensions" to "extension_pack",
    "git" to "branch",
    "commands" to "palette",
    "files" to "project",
)

/**
 * The one place a token (a shell name, a pack's icon token, a glyph name) becomes a vector.
 * `ei` prefers the custom glyph and falls back to Material per token; `material` prefers
 * Material and falls back to the custom glyph, so a glyph with no stand-in still shows.
 * Null when neither set knows the token.
 */
fun resolveIconOrNull(token: String, style: IconStyle): ImageVector? {
    val custom = EiIcons.vector(CUSTOM_ALIASES[token] ?: token)
    val material = MATERIAL_ICONS[token]
    return when (style) {
        IconStyle.EI -> custom ?: material
        IconStyle.MATERIAL -> material ?: custom
    }
}

/** [resolveIconOrNull] with the unknown-token glyph, for slots that must always draw something. */
fun resolveIcon(token: String, style: IconStyle): ImageVector = resolveIconOrNull(token, style) ?: UNKNOWN_ICON

/** [resolveIcon] under the current `appearance.iconStyle`. */
@Composable
@ReadOnlyComposable
fun iconFor(token: String): ImageVector = resolveIcon(token, LocalFeel.current.iconStyle)

/** [resolveIconOrNull] under the current `appearance.iconStyle`. */
@Composable
@ReadOnlyComposable
fun iconOrNull(token: String): ImageVector? = resolveIconOrNull(token, LocalFeel.current.iconStyle)
