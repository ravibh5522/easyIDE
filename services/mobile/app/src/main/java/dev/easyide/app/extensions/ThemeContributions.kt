package dev.easyide.app.extensions

import dev.easyide.app.extensions.adapters.ContributedThemes
import dev.easyide.app.ui.theme.ThemeTokens
import dev.easyide.extensions.action.ExtensionLog
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.action.LogLevel
import dev.easyide.extensions.contrib.IconThemeContribution
import dev.easyide.extensions.contrib.Owned
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.contrib.ThemeContribution
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapter seam for `contributes.themes` and `iconThemes` (EXT-22, customization.md
 * sec 8-9). Registered once in [ExtensionsContainer], fed from the registry's theme
 * stores on every change.
 */
interface ThemeContributionAdapter {
    fun update(themes: List<Owned<ThemeContribution>>, iconThemes: List<Owned<IconThemeContribution>>)
}

/**
 * The live list of contributed themes (enabled extensions only, conflict-resolved
 * labels) and their loaded tokens. A theme file is read and mapped once per file
 * version on [io] ([ContributedThemes.resolve]); a failure is logged to the
 * Extension Log once and remembered, so the picker and the active theme never
 * re-read a broken file. Icon themes are listed only (sec 9 is not built yet).
 */
class ContributedThemeCatalog(
    private val log: ExtensionLog,
    private val io: CoroutineDispatcher,
    private val read: (String) -> String? = ::readThemeFile,
) : ThemeContributionAdapter {
    private val themeState = MutableStateFlow<List<Owned<ThemeContribution>>>(emptyList())
    private val iconThemeState = MutableStateFlow<List<Owned<IconThemeContribution>>>(emptyList())

    val themes: StateFlow<List<Owned<ThemeContribution>>> = themeState.asStateFlow()
    val iconThemes: StateFlow<List<Owned<IconThemeContribution>>> = iconThemeState.asStateFlow()

    /** Keyed by the contribution itself: its host path changes with every install or update. */
    private val loaded = ConcurrentHashMap<ThemeContribution, Loaded>()

    private class Loaded(val tokens: ThemeTokens?)

    override fun update(themes: List<Owned<ThemeContribution>>, iconThemes: List<Owned<IconThemeContribution>>) {
        val live = themes.mapTo(HashSet()) { it.value }
        loaded.keys.retainAll(live)
        themeState.value = themes
        iconThemeState.value = iconThemes
    }

    /** [theme]'s tokens over its base palette, or null when its file is unusable (already logged). */
    suspend fun tokensFor(theme: Owned<ThemeContribution>): ThemeTokens? {
        loaded[theme.value]?.let { return it.tokens }
        val tokens = withContext(io) {
            val owner = (theme.owner as? Owner.Ext)?.id
            ContributedThemes.resolve(
                theme.value, read,
                warn = { log.append(LogEntry(owner, LogLevel.WARN, it)) },
                info = { log.append(LogEntry(owner, LogLevel.INFO, it)) },
            ).also { if (it == null) log.append(LogEntry(owner, LogLevel.ERROR, "theme '${theme.value.label}' could not be loaded; using the built-in palette")) }
        }
        loaded.putIfAbsent(theme.value, Loaded(tokens))
        return tokens
    }

    /**
     * The tokens to apply for [selection] (`workbench.colorTheme`): null for the
     * built-in palette, which is also what an unknown, disabled or uninstalled
     * theme, or one that fails to load, resolves to.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun active(selection: Flow<String>): Flow<ThemeTokens?> =
        combine(selection, themes) { s, t -> ContributedThemes.find(s, t) }
            .distinctUntilChanged()
            .mapLatest { it?.let { theme -> tokensFor(theme) } }
            .distinctUntilChanged()
}

private fun readThemeFile(hostPath: String): String? {
    val file = File(hostPath)
    return try {
        if (!file.isFile || file.length() > ExtensionUiPolicy.THEME_FILE_MAX_BYTES) null else file.readText()
    } catch (e: IOException) {
        null
    }
}
