package dev.easyide.app.ui.shell.host

/**
 * The one place app-scope panels and document pages are bound to their composables.
 *
 * R1 swap list, once the sibling branches merge: `panels` binds `home.projects` to `HomePanel`,
 * `settings.categories` to `SettingsPanel(selected, onSelect)` and `extensions.list` to
 * `ExtensionsPanel`; `documents` binds `easyide.project` to `ProjectPage(projectId)`,
 * `easyide.settings` to `SettingsPage(category)` and `easyide.extension` to `ExtensionPage(id)`
 * (ids in [AppDocuments]). Then `LegacyAdapters.kt` goes.
 */
object AppRenderers {
    fun panels(deps: ShellDeps): PanelRendererRegistry = legacyPanels(deps)

    fun documents(): DocumentRendererRegistry = DocumentRendererRegistry()
}
