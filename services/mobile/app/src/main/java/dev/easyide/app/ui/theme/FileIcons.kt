package dev.easyide.app.ui.theme

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import dev.easyide.app.data.settings.SettingsPolicy
import dev.easyide.app.extensions.ActiveIconTheme
import dev.easyide.app.extensions.IconThemeChoice
import dev.easyide.app.extensions.adapters.IconTheme
import dev.easyide.app.ui.screens.workspace.syntax.TextMateHighlighter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The active icon theme's images for the file tree (customization.md sec 9). Lookups and PNG
 * decoding run on IO; decoded images are shared through an LRU of
 * [SettingsPolicy.ICON_CACHE_ENTRIES], keyed by file path.
 */
class FileIcons(private val theme: IconTheme) {

    suspend fun file(name: String, light: Boolean): ImageBitmap? = withContext(Dispatchers.IO) {
        val path = theme.fileIcon(name, TextMateHighlighter.languageIdFor(name, null), light)
        path?.let(::decode)
    }

    suspend fun folder(name: String, expanded: Boolean, light: Boolean): ImageBitmap? = withContext(Dispatchers.IO) {
        theme.folderIcon(name, expanded, light)?.let(::decode)
    }

    private fun decode(path: String): ImageBitmap? {
        cache.get(path)?.let { return it }
        val file = File(path)
        if (!file.isFile || file.length() > ActiveIconTheme.ICON_FILE_MAX_BYTES) return null
        val bitmap = BitmapFactory.decodeFile(path)?.asImageBitmap() ?: return null
        cache.put(path, bitmap)
        return bitmap
    }

    private companion object {
        val cache = LruCache<String, ImageBitmap>(SettingsPolicy.ICON_CACHE_ENTRIES)
    }
}

/** The active icon theme's images; null means the built-in icons. */
val LocalFileIcons = staticCompositionLocalOf<FileIcons?> { null }

/** Icon themes of enabled extensions, for the `workbench.iconTheme` picker. */
val LocalIconThemeChoices = staticCompositionLocalOf<List<IconThemeChoice>> { emptyList() }

/**
 * The icon theme's image for a tree row, or null (no theme, no match, still loading, or not
 * decodable), in which case the row keeps its built-in icon.
 */
@Composable
fun rememberThemedFileIcon(name: String, isDirectory: Boolean, expanded: Boolean): ImageBitmap? {
    val icons = LocalFileIcons.current ?: return null
    // The `light` section applies when the resolved base is light.
    val light = editorColors.background.luminance() > LIGHT_BACKGROUND_LUMINANCE
    val image by produceState<ImageBitmap?>(null, icons, name, isDirectory, expanded, light) {
        value = if (isDirectory) icons.folder(name, expanded, light) else icons.file(name, light)
    }
    return image
}

private const val LIGHT_BACKGROUND_LUMINANCE = 0.5f
