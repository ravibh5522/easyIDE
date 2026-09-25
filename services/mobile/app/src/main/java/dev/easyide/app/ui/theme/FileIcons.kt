package dev.easyide.app.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.easyide.app.data.settings.SettingsPolicy
import dev.easyide.app.extensions.ActiveIconTheme
import dev.easyide.app.extensions.IconThemeChoice
import dev.easyide.app.extensions.adapters.IconTheme
import dev.easyide.app.extensions.adapters.SvgParser
import dev.easyide.app.ui.screens.workspace.syntax.TextMateHighlighter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The active icon theme's images for file rows (customization.md sec 9). Lookups, SVG parsing and
 * rasterising, and PNG decoding run on IO; results are shared through an LRU of
 * [SettingsPolicy.ICON_CACHE_ENTRIES], keyed by file path and pixel size, so a row's icon is drawn
 * once at the size the screen needs and never rescaled.
 */
class FileIcons(private val theme: IconTheme) {

    /** The theme draws its icons from a font, which the app cannot render: every row keeps its built-in glyph. */
    val fontIconsOnly: Boolean get() = theme.icons.isEmpty() && theme.fontIcons > 0

    suspend fun file(name: String, light: Boolean, px: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val path = theme.fileIcon(name, TextMateHighlighter.languageIdFor(name, null), light)
        path?.let { load(it, px) }
    }

    suspend fun folder(name: String, expanded: Boolean, light: Boolean, px: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        theme.folderIcon(name, expanded, light)?.let { load(it, px) }
    }

    suspend fun rootFolder(expanded: Boolean, light: Boolean, px: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        theme.rootFolderIcon(expanded, light)?.let { load(it, px) }
    }

    /** One icon by its theme-relative id, for the picker's preview strip. */
    suspend fun preview(iconPath: String, px: Int): ImageBitmap? = withContext(Dispatchers.IO) { load(iconPath, px) }

    private fun load(path: String, px: Int): ImageBitmap? {
        val key = "$path@$px"
        cache.get(key)?.let { return it }
        val file = File(path)
        if (!file.isFile || file.length() > ActiveIconTheme.ICON_FILE_MAX_BYTES) return null
        val bitmap = when (file.extension.lowercase()) {
            "svg" -> SvgParser.parse(file.readText())?.let { SvgRaster.render(it, px) }
            else -> BitmapFactory.decodeFile(path)
        } ?: return null
        return bitmap.asImageBitmap().also { cache.put(key, it) }
    }

    private companion object {
        val cache = LruCache<String, ImageBitmap>(SettingsPolicy.ICON_CACHE_ENTRIES)
    }
}

/** The active icon theme's images; null means the built-in icons. */
val LocalFileIcons = staticCompositionLocalOf<FileIcons?> { null }

/** Icon themes of enabled extensions, for the `workbench.iconTheme` picker. */
val LocalIconThemeChoices = staticCompositionLocalOf<List<IconThemeChoice>> { emptyList() }

/** What a file row shows from the active icon theme. */
sealed interface ThemedIcon {
    /** No icon theme is active, or it has no image for this entry: the row keeps its built-in glyph. */
    data object None : ThemedIcon

    /** The theme's image is being read; the row leaves its icon slot empty so the glyph does not flash. */
    data object Loading : ThemedIcon

    class Ready(val image: ImageBitmap) : ThemedIcon
}

/**
 * The icon theme's image for a file or folder row at [size], rasterised for the current density.
 * The `light` section applies when the resolved base is light.
 */
@Composable
fun rememberThemedFileIcon(name: String, isDirectory: Boolean, expanded: Boolean, size: Dp): ThemedIcon {
    val icons = LocalFileIcons.current ?: return ThemedIcon.None
    val light = editorColors.background.luminance() > LIGHT_BACKGROUND_LUMINANCE
    val px = with(LocalDensity.current) { size.roundToPx() }
    val state by produceState<ThemedIcon>(ThemedIcon.Loading, icons, name, isDirectory, expanded, light, px) {
        val image = if (isDirectory) icons.folder(name, expanded, light, px) else icons.file(name, light, px)
        value = image?.let { ThemedIcon.Ready(it) } ?: ThemedIcon.None
    }
    return state
}

private const val LIGHT_BACKGROUND_LUMINANCE = 0.5f
