package dev.easyide.app.ui.shell.host

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.easyide.app.ui.shell.DocumentType
import dev.easyide.app.ui.shell.DocumentUri

/** Draws the body of one kind of document. Panels and documents are plain composables: the shell owns every scaffold and bar. */
fun interface DocumentRenderer {
    @Composable
    fun Render(uri: DocumentUri, modifier: Modifier)
}

/** Draws the list a container shows in a panel. */
fun interface PanelRenderer {
    @Composable
    fun Render(modifier: Modifier)
}

/**
 * A container's renderer. [spansWindow] is for the temporary adapters over the pre-shell screens
 * only: those screens are whole pages with their own bars, so they take the space of panel and
 * stage together instead of a docked column. It goes away with them (tracker, R1).
 */
class PanelBinding(val renderer: PanelRenderer, val spansWindow: Boolean = false)

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
}

/** Panel renderers by container id; a container with none gets the "panel unavailable" message. */
class PanelRendererRegistry(private val byContainer: Map<String, PanelBinding> = emptyMap()) {
    fun binding(containerId: String): PanelBinding? = byContainer[containerId]
}
