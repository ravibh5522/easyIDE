package dev.easyide.app.extensions

import dev.easyide.extensions.contrib.IconThemeContribution
import dev.easyide.extensions.contrib.Owned
import dev.easyide.extensions.contrib.ThemeContribution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Adapter seam for `contributes.themes` and `iconThemes` (EXT-22, customization.md
 * sec 8-9). Registered once in [ExtensionsContainer], fed from the registry's theme
 * stores on every change.
 *
 * The theme-token rebuild owns reading theme files into the app's colour tokens
 * (`ThemeColorMap`, `tokenColors` -> `SyntaxRole`) and the `workbench.colorTheme` /
 * `workbench.iconTheme` pickers. What exists now is implemented: [ContributedThemeCatalog]
 * keeps the live list of contributed themes (conflict-resolved labels, file host paths),
 * which is what a picker lists and a loader reads.
 */
interface ThemeContributionAdapter {
    fun update(themes: List<Owned<ThemeContribution>>, iconThemes: List<Owned<IconThemeContribution>>)
}

class ContributedThemeCatalog : ThemeContributionAdapter {
    private val themeState = MutableStateFlow<List<Owned<ThemeContribution>>>(emptyList())
    private val iconThemeState = MutableStateFlow<List<Owned<IconThemeContribution>>>(emptyList())

    val themes: StateFlow<List<Owned<ThemeContribution>>> = themeState.asStateFlow()
    val iconThemes: StateFlow<List<Owned<IconThemeContribution>>> = iconThemeState.asStateFlow()

    override fun update(themes: List<Owned<ThemeContribution>>, iconThemes: List<Owned<IconThemeContribution>>) {
        themeState.value = themes
        iconThemeState.value = iconThemes
    }
}
