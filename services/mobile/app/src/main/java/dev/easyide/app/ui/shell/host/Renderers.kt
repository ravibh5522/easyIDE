package dev.easyide.app.ui.shell.host

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.easyide.app.ui.shell.DocumentRegistry
import dev.easyide.app.ui.shell.DocumentType
import dev.easyide.app.ui.shell.DocumentUri

/**
 * Draws the body of one kind of document. Panels and documents are plain composables: the shell owns every scaffold and bar.
 * [Title] is the subject's own name (a project's, an extension's) for the tab and the compact title bar; it is
 * composable so it follows a rename, and null keeps the type's fixed title.
 */
fun interface DocumentRenderer {
    @Composable
    fun Render(uri: DocumentUri, modifier: Modifier)

    @Composable
    fun Title(uri: DocumentUri): String? = null
}

/** Draws the list a container shows in a panel. */
fun interface PanelRenderer {
    @Composable
    fun Render(modifier: Modifier)
}

/**
 * A container's renderer. [stageDefault] is what the stage shows on a wide window while no document
 * is open (Home's "Now" page); null leaves the stage on its empty message.
 */
class PanelBinding(val renderer: PanelRenderer, val stageDefault: PanelRenderer? = null)

/** What a document body resolves to: a renderer, or the placeholder for a type nothing here can draw. */
sealed interface DocumentChoice {
    data class Render(val renderer: DocumentRenderer) : DocumentChoice
    data object Unavailable : DocumentChoice
}

/**
 * Renderers by document type id. A URI whose type is [DocumentType.UNAVAILABLE] (a disabled
 * extension, a page a newer app wrote) and a type nobody registered a renderer for both resolve
 * to [DocumentChoice.Unavailable], so restore and open never fail on the UI side either.
 */
class DocumentRendererRegistry(private val byType: Map<String, DocumentRenderer> = emptyMap()) {
    fun choose(type: DocumentType): DocumentChoice =
        byType[type.id]?.takeIf { type.id != DocumentType.UNAVAILABLE_ID }?.let(DocumentChoice::Render) ?: DocumentChoice.Unavailable

    /** This registry's renderers and [other]'s (the workspace adds its files to the app's pages). */
    operator fun plus(other: DocumentRendererRegistry) = DocumentRendererRegistry(byType + other.byType)
}

/** The title of [uri]'s tab: the renderer's own subject name, else the fixed title of its type. */
@Composable
fun DocumentRendererRegistry.titleOf(documents: DocumentRegistry, uri: DocumentUri): String {
    val type = documents.resolve(uri)
    val named = (choose(type) as? DocumentChoice.Render)?.renderer?.Title(uri)
    return named ?: type.title(uri)
}

/** Panel renderers by container id; a container with none gets the "panel unavailable" message. */
class PanelRendererRegistry(private val byContainer: Map<String, PanelBinding> = emptyMap()) {
    fun binding(containerId: String): PanelBinding? = byContainer[containerId]

    operator fun plus(other: PanelRendererRegistry) = PanelRendererRegistry(byContainer + other.byContainer)
}

/** A [PanelRenderer] from a composable lambda: the app's bindings are all of this shape. */
fun panelRenderer(content: @Composable (Modifier) -> Unit): PanelRenderer = object : PanelRenderer {
    @Composable
    override fun Render(modifier: Modifier) = content(modifier)
}

/** A [DocumentRenderer] from a body and, optionally, the subject's own name for the tab. */
fun documentRenderer(
    title: @Composable (DocumentUri) -> String? = { null },
    content: @Composable (DocumentUri, Modifier) -> Unit,
): DocumentRenderer = object : DocumentRenderer {
    @Composable
    override fun Render(uri: DocumentUri, modifier: Modifier) = content(uri, modifier)

    @Composable
    override fun Title(uri: DocumentUri): String? = title(uri)
}
