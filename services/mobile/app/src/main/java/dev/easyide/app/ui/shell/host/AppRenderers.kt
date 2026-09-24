package dev.easyide.app.ui.shell.host

import androidx.compose.runtime.Composable
import dev.easyide.app.ui.shell.CoreShell

/**
 * The one place app-scope panels and document pages are bound to their composables (screens.md
 * section 1): Home, Settings and Extensions each give a list for the primary panel and a page for
 * the stage. Container and document ids are the ones in [CoreShell] and [AppDocuments]. The tables
 * are read by id, so a test can hold them against the registries without building the app.
 */
object AppRenderers {
    private val panelBindings: Map<String, (ShellDeps) -> PanelBinding> = mapOf(
        CoreShell.HOME_PROJECTS to { deps -> PanelBinding(homePanel(deps), stageDefault = homeNow(deps)) },
        CoreShell.SETTINGS_CATEGORIES to { deps -> PanelBinding(settingsPanel(deps), stageDefault = settingsDefault(deps)) },
        CoreShell.EXTENSIONS_LIST to { deps -> PanelBinding(extensionsPanel(deps)) },
    )

    private val documentBindings: Map<String, (ShellDeps) -> DocumentRenderer> = mapOf(
        AppDocuments.PROJECT to ::projectDocument,
        AppDocuments.SETTINGS to ::settingsDocument,
        AppDocuments.EXTENSION to ::extensionDocument,
    )

    /** The containers and document types that have a renderer. */
    val containerIds: Set<String> get() = panelBindings.keys
    val documentTypeIds: Set<String> get() = documentBindings.keys

    fun panels(deps: ShellDeps): PanelRendererRegistry = PanelRendererRegistry(panelBindings.mapValues { (_, bind) -> bind(deps) })

    fun documents(deps: ShellDeps): DocumentRendererRegistry = DocumentRendererRegistry(documentBindings.mapValues { (_, bind) -> bind(deps) })

    /** Dialogs that belong to no single panel or page: one copy of each, or every question would show twice. */
    @Composable
    fun Dialogs(deps: ShellDeps) {
        HomeDialogs(deps)
        ExtensionsDialogsHost(deps)
    }
}
