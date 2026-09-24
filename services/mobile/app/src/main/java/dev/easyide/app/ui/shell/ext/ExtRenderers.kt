package dev.easyide.app.ui.shell.ext

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.easyide.app.R
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitTabs
import dev.easyide.app.ui.shell.DocumentType
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.host.DocumentRenderer
import dev.easyide.app.ui.shell.host.PanelBinding
import dev.easyide.app.ui.shell.host.ShellDeps
import dev.easyide.app.ui.shell.host.documentRenderer
import dev.easyide.app.ui.shell.host.panelRenderer
import dev.easyide.extensions.view.ViewScope
import dev.easyide.extensions.whenclause.WhenEvaluator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renderers for what extensions contribute, looked up by id when the shell asks: a container is drawn as its views, a document
 * type as its body. They are fallbacks of the registries, not entries, because packs come and go while the shell runs.
 */
object ExtRenderers {

    /** The binding for an extension container, or null for any other id. */
    fun panel(deps: ShellDeps, containerId: String): PanelBinding? {
        val ext = deps.container.extensions.shell.value
        return if (ext.packOfContainer(containerId) != null) PanelBinding(panelRenderer { m -> ContainerPanel(deps, containerId, m) }) else null
    }

    /** The renderer for an extension document type, or null for any other. */
    fun document(deps: ShellDeps, type: DocumentType): DocumentRenderer? {
        if (deps.container.extensions.shell.value.document(type.id) == null) return null
        return documentRenderer(title = { uri -> documentTitle(deps, type.id, uri) }) { uri, m -> DocumentBody(deps, type.id, uri, m) }
    }
}

/** A container: its title, then its views (tabs when there are several). Each view is fetched only while it is showing. */
@Composable
private fun ContainerPanel(deps: ShellDeps, containerId: String, modifier: Modifier) {
    val extensions = deps.container.extensions
    val ext by extensions.shell.collectAsState()
    val context by extensions.runtime.contextKeys.snapshot.collectAsState()
    val container = ext.containers.firstOrNull { it.spec.id == containerId }
    val views = ext.views[containerId].orEmpty().filter { it.condition == null || WhenEvaluator.evaluate(it.condition!!, context) }
    var selected by rememberSaveable(containerId) { mutableIntStateOf(0) }
    Column(modifier) {
        container?.let {
            BasicText(it.spec.title, Modifier.fillMaxWidth().padding(Kit.space.l).semantics { heading() }, style = Kit.text.heading.copy(color = Kit.colors.plainText))
        }
        when {
            views.isEmpty() -> KitEmptyState(EmptyArt.Prompt, stringResource(R.string.extview_container_empty))
            views.size == 1 -> ViewBody(deps, views.single(), Modifier.weight(1f))
            else -> {
                val at = selected.coerceIn(0, views.lastIndex)
                KitTabs(views.map { it.name }, at, { selected = it })
                ViewBody(deps, views[at], Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ViewBody(deps: ShellDeps, view: ExtView, modifier: Modifier) {
    val extensions = deps.container.extensions
    DisposableEffect(view.id) {
        extensions.viewData.driver.showView(view.id)
        onDispose { extensions.viewData.driver.hideView(view.id) }
    }
    ExtViewSurface(view.body, view.id, view.extensionId, view.name, extensions.viewData.host, modifier)
}

/** What a document's own data seeds it with: the URI it was opened at and its key. */
private fun seedOf(uri: DocumentUri) = JsonObject(mapOf("uri" to JsonPrimitive(uri.key.toString()), "key" to JsonPrimitive(uri.segments.getOrElse(1) { "" })))

/** The tab title: the type's `title` template over the document's data (`{name}`), else its key. */
@Composable
private fun documentTitle(deps: ShellDeps, typeId: String, uri: DocumentUri): String? {
    val extensions = deps.container.extensions
    val doc = extensions.shell.collectAsState().value.document(typeId) ?: return null
    val seed = remember(uri.key) { seedOf(uri) }
    val data by extensions.viewData.hub.flow(uri.key.toString(), JsonObject(doc.body.state + seed)).collectAsState(JsonObject(doc.body.state + seed))
    return doc.title.resolve(ViewScope.of(data), extensions.viewData.host.now()).ifBlank { null }
}

@Composable
private fun DocumentBody(deps: ShellDeps, typeId: String, uri: DocumentUri, modifier: Modifier) {
    val extensions = deps.container.extensions
    val ext by extensions.shell.collectAsState()
    val doc = ext.document(typeId)
    if (doc == null) {
        KitEmptyState(EmptyArt.Offline, stringResource(R.string.extview_document_unavailable, uri.toString()), modifier)
        return
    }
    val key = uri.key.toString()
    val seed = remember(key) { seedOf(uri) }
    DisposableEffect(key) {
        extensions.viewData.driver.showDocument(typeId, key, uri.segments.getOrElse(1) { "" })
        onDispose { extensions.viewData.driver.hideDocument(key) }
    }
    ExtViewSurface(doc.body, key, doc.extensionId, doc.type.title(uri), extensions.viewData.host, modifier, seed)
}
