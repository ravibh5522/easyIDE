package dev.easyide.app.ui.shell.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.ui.appViewModel
import dev.easyide.app.ui.screens.settings.LayoutCatalog
import dev.easyide.app.ui.screens.settings.SettingsCategory
import dev.easyide.app.ui.screens.settings.SettingsHost
import dev.easyide.app.ui.screens.settings.SettingsLink
import dev.easyide.app.ui.screens.settings.SettingsPage
import dev.easyide.app.ui.screens.settings.SettingsPanel
import dev.easyide.app.ui.screens.settings.SettingsViewModel
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.LayoutPresets

/*
 * Settings on the shell: the category list as the primary panel, one category as the document
 * `easyide://settings/<category>`. On a wide window the stage shows Appearance until a category is
 * picked, so the pair is never a list next to an empty page.
 */

private val DEFAULT_CATEGORY = SettingsCategory.APPEARANCE.id

/** What settings pages ask of the shell: links out, the toast, and the layout the shell can offer. */
@Composable
private fun rememberSettingsHost(deps: ShellDeps): SettingsHost {
    val shell = LocalShellActions.current
    val registries by deps.registries.collectAsStateWithLifecycle()
    val extensionPresets by deps.presets.collectAsStateWithLifecycle()
    return remember(deps, shell, registries, extensionPresets) {
        SettingsHost(
            externalFolderSync = deps.container.externalFolderSync,
            onOpenExtensions = { shell.goTo(CoreShell.EXTENSIONS) },
            onOpenDiagnostics = deps.exits.onOpenDiagnostics,
            onNotify = shell.notify,
            layout = LayoutCatalog.of(registries.navigation, registries.containers),
            presets = LayoutPresets.BUILT_IN + extensionPresets,
        )
    }
}

internal fun settingsPanel(deps: ShellDeps) = panelRenderer { modifier ->
    val viewModel: SettingsViewModel = appViewModel(deps.factory)
    val shell = LocalShellActions.current
    val state = LocalShellState.current
    SettingsPanel(
        viewModel = viewModel,
        selected = AppDocuments.settingsCategoryOf(state.activeDocument) ?: DEFAULT_CATEGORY.takeIf { !state.compact },
        onSelect = { category -> DocumentUri.easyide("settings", category)?.let(shell.open) },
        modifier = modifier,
    )
}

/** The page the stage shows on a wide window before any category was picked. */
internal fun settingsDefault(deps: ShellDeps) = panelRenderer { modifier ->
    val viewModel: SettingsViewModel = appViewModel(deps.factory)
    SettingsPage(viewModel, DEFAULT_CATEGORY, rememberSettingsHost(deps), modifier)
}

internal fun settingsDocument(deps: ShellDeps) = documentRenderer(
    title = { uri -> SettingsCategory.ofId(AppDocuments.settingsCategoryOf(uri))?.let { stringResource(it.title) } },
) { uri, modifier ->
    val viewModel: SettingsViewModel = appViewModel(deps.factory)
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val category = AppDocuments.settingsCategoryOf(uri) ?: DEFAULT_CATEGORY
    // `easyide://settings/extensions#<id>` marks the first row of that extension: the page scrolls to a marked row.
    val row = SettingsLink.rowFor(uri.fragment.takeIf { category == SettingsCategory.EXTENSIONS.id }, ui.settings.schema.settings)
    LaunchedEffect(uri, row) { if (row != null) viewModel.onHighlight(row) }
    SettingsPage(viewModel, category, rememberSettingsHost(deps), modifier)
}
