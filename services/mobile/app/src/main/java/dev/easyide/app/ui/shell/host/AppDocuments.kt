package dev.easyide.app.ui.shell.host

import dev.easyide.app.R
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.ContainerRegistry
import dev.easyide.app.ui.shell.DocumentRegistry
import dev.easyide.app.ui.shell.DocumentType
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.IconRef
import dev.easyide.app.ui.shell.NavRegistry
import dev.easyide.app.ui.shell.Origin
import dev.easyide.app.ui.shell.UriPattern

/** The registries the app-scope shell starts from: core containers and navigation, core document types. */
class AppRegistries(val documents: DocumentRegistry, val containers: ContainerRegistry, val navigation: NavRegistry)

/**
 * The document types of app scope (screens.md section 1): a settings page, an extension page, a
 * project page. Titles are one word per kind: the page draws its own header with the subject's
 * name, so a tab or a compact title bar never needs a lookup that could go stale.
 */
object AppDocuments {
    const val SETTINGS = "easyide.settings"
    const val EXTENSION = "easyide.extension"
    const val PROJECT = "easyide.project"

    fun settingsPage(category: String? = null): DocumentUri = requireNotNull(DocumentUri.easyide("settings", category))
    fun extensionPage(id: String): DocumentUri = requireNotNull(DocumentUri.easyide("extension", id))
    fun projectPage(id: String): DocumentUri = requireNotNull(DocumentUri.easyide("project", id))

    private fun type(id: String, page: String, icon: String, title: String) =
        DocumentType(id, UriPattern("easyide", page), { title }, { IconRef(icon) }, supportsSplit = false)

    fun registries(text: (Int) -> String): AppRegistries {
        val types = listOf(
            type(SETTINGS, "settings", "settings", text(R.string.shell_type_settings)),
            type(EXTENSION, "extension", "extensions", text(R.string.shell_type_extension)),
            type(PROJECT, "project", "home", text(R.string.shell_type_project)),
        )
        val documents = types.fold(DocumentRegistry.EMPTY) { r, t -> r.register(t, Origin.Core).registry }
        return AppRegistries(documents, CoreShell.containers(), CoreShell.navigation())
    }
}
