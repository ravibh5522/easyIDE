package dev.easyide.app.ui.shell.ext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import dev.easyide.app.extensions.adapters.ShellContributions
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.shell.IconRef
import dev.easyide.app.ui.icons.resolveIcon
import dev.easyide.app.ui.props.IconStyle
import dev.easyide.extensions.contrib.CommandIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Icons of extension views: the action glyphs an author names by token (`play`, `stop`, `refresh`...) and a pack's own
 * SVG, read from its file and drawn as a vector the shell tints. A token this table does not know falls to the navigation
 * icons and then to a generic glyph, so a control is never blank; a file that cannot be read stays that glyph.
 */
object ExtIcons {
    /** Author-facing action tokens and the resolver token each draws (the resolver owns every glyph). */
    private val TOKENS: Map<String, String> = mapOf(
        "play" to "play_arrow", "stop" to "stop", "refresh" to "refresh", "restart" to "refresh",
        "add" to "add", "delete" to "delete", "trash" to "delete", "edit" to "edit",
        "check" to "check", "close" to "close", "send" to "send", "copy" to "content_copy",
        "info" to "info", "warning" to "warning", "star" to "star", "chat" to "chat",
    )

    private val loaded = mutableStateMapOf<String, ImageVector>()
    private val loading = HashSet<String>()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The glyph for [ref]: a token, or the pack's SVG once it has been read (until then the generic glyph). */
    fun of(ref: IconRef, ink: Color): ImageVector {
        val name = ref.name
        if (!name.startsWith(ShellContributions.EXT_ICON_PREFIX)) return resolveIcon(TOKENS[name] ?: name, IconStyle.EI)
        loaded[name]?.let { return it }
        load(name, ink)
        return resolveIcon(GENERIC, IconStyle.EI)
    }

    @Synchronized
    private fun load(name: String, ink: Color) {
        if (!loading.add(name)) return
        io.launch {
            val icon = read(name.removePrefix(ShellContributions.EXT_ICON_PREFIX))?.let { SvgIcon.parse(it) }
            if (icon != null) loaded[name] = vectorOf(name, icon, ink)
        }
    }

    /** I/O boundary: a pack's icon file, at most the validated size; unreadable gives null. */
    private fun read(path: String): String? = try { File(path).readText() } catch (e: IOException) { null }

    private fun vectorOf(name: String, icon: SvgIcon, ink: Color): ImageVector {
        val builder = ImageVector.Builder(name, GRID, GRID, GRID_UNITS, GRID_UNITS)
        icon.shapes.forEach { s ->
            builder.addPath(
                PathParser().parsePathString(s.data).toNodes(),
                fill = if (s.fill) SolidColor(ink) else null,
                stroke = if (s.stroke) SolidColor(ink) else null,
                strokeLineWidth = s.strokeWidth,
                strokeLineCap = if (s.round) StrokeCap.Round else StrokeCap.Butt,
                strokeLineJoin = if (s.round) StrokeJoin.Round else StrokeJoin.Miter,
            )
        }
        return builder.build()
    }

    private const val GENERIC = "extensions"
    private const val GRID_UNITS = 24f
    private val GRID = 24.dp
}

/** The vector of [icon] for drawing now; a pack SVG appears once read. */
@Composable
internal fun rememberExtIcon(icon: CommandIcon): ImageVector {
    val ink = Kit.colors.plainText
    val ref = remember(icon) { ShellContributions.icon(icon) }
    return ExtIcons.of(ref, ink)
}
