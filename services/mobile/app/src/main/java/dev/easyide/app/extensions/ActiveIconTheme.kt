package dev.easyide.app.extensions

import dev.easyide.app.extensions.adapters.IconTheme
import dev.easyide.app.extensions.adapters.IconOverrides
import dev.easyide.app.extensions.adapters.IconThemeFile
import dev.easyide.extensions.contrib.IconThemeContribution
import dev.easyide.extensions.contrib.Owned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** An icon theme the picker offers: its `workbench.iconTheme` id and display label. */
data class IconThemeChoice(val id: String, val label: String)

/**
 * `workbench.iconTheme` (customization.md sec 9): the contributed icon theme whose id is
 * selected, read and parsed on [io] once per selection or contribution change, with the user's
 * [overrides] (`easyide.icons.*Associations`) laid in front, which re-apply without re-reading the
 * files. Null - the built-in file-type icons - for an empty selection, an id no enabled extension
 * contributes, or a file that cannot be read (logged through [warn]).
 */
class ActiveIconTheme(
    iconThemes: StateFlow<List<Owned<IconThemeContribution>>>,
    selection: Flow<String>,
    overrides: Flow<IconOverrides>,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope,
    private val warn: (String) -> Unit,
    private val read: (File) -> String? = ::readIconThemeFile,
) {
    /** Every enabled icon theme, in contribution order. */
    val choices: StateFlow<List<IconThemeChoice>> = iconThemes
        .map { list -> list.map { IconThemeChoice(it.value.id, it.value.label) }.distinctBy { it.id } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val loaded: StateFlow<IconTheme?> = combine(selection.distinctUntilChanged(), iconThemes) { id, list ->
        if (id.isEmpty()) null else list.firstOrNull { it.value.id == id }?.value
    }
        .distinctUntilChanged()
        .mapLatest { it?.let { c -> withContext(io) { load(c) } } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val theme: StateFlow<IconTheme?> = combine(loaded, overrides) { t, o -> t?.copy(overrides = o) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The active theme's icon ids, for checking the user's associations; null while built-in icons are active. */
    val iconIds: StateFlow<Set<String>?> = loaded.map { it?.icons?.keys }.stateIn(scope, SharingStarted.Eagerly, null)

    private fun load(c: IconThemeContribution): IconTheme? {
        val file = File(c.file.hostPath)
        val root = File(c.file.hostPath.removeSuffix(c.file.path).ifEmpty { file.parent.orEmpty() })
        val text = read(file) ?: return null.also { warn("icon theme '${c.id}': ${c.file.path} unreadable") }
        var skipped = 0
        val theme = IconThemeFile.parse(c.id, text, file, root) { skipped++ }
            ?: return null.also { warn("icon theme '${c.id}': ${c.file.path} is not a JSON object") }
        if (skipped > 0) warn("icon theme '${c.id}': $skipped icons skipped (PNG and SVG only)")
        if (theme.fontIcons > 0) warn("icon theme '${c.id}': ${theme.fontIcons} font icons skipped (icon fonts are not supported)")
        return theme
    }

    companion object {
        /** Icon theme files are small maps; anything larger is refused unread. */
        const val ICON_THEME_FILE_MAX_BYTES = 1L * 1024 * 1024

        /** A single icon image larger than this is not decoded. */
        const val ICON_FILE_MAX_BYTES = 256L * 1024
    }
}

private fun readIconThemeFile(file: File): String? = try {
    if (!file.isFile || file.length() > ActiveIconTheme.ICON_THEME_FILE_MAX_BYTES) null else file.readText()
} catch (e: IOException) {
    null
}
