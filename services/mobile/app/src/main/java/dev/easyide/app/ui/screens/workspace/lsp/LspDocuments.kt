package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.app.ui.screens.workspace.EditorTab
import dev.easyide.app.ui.screens.workspace.syntax.LanguageConfigs
import dev.easyide.lsp.client.DocContext
import dev.easyide.lsp.client.LspClient
import dev.easyide.lsp.docs.DocumentStore
import dev.easyide.lsp.workspace.FileUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One editor tab as the language servers see it. */
data class OpenDoc(val path: String, val uri: String, val languageId: String, val fileName: String)

/**
 * Document lifecycle from the editor tabs to `:lsp` (lsp-client.md sec 5): a tab opening is
 * `didOpen`, each buffer change `didChange` (the store debounces and diffs), a save `didSave`,
 * a close `didClose`. Only editable project files take part - binary previews, truncated views
 * and environment files opened read-only are not synced.
 *
 * Also keeps the last few texts per document by version, so results computed for an older
 * version can be placed against the text they refer to (the decoration model then carries
 * them forward, lsp-features.md 3.3).
 */
class LspDocuments(
    private val client: LspClient,
    private val store: DocumentStore,
    private val environmentId: String,
    private val projectId: String,
    private val projectRoot: File,
) {
    private val byPath = ConcurrentHashMap<String, OpenDoc>()
    private val lastText = ConcurrentHashMap<String, String>()
    private val history = ConcurrentHashMap<String, ArrayDeque<Pair<Int, String>>>()
    private val languageCache = ConcurrentHashMap<String, String>()

    private val pathsFlow = MutableStateFlow<Set<String>>(emptySet())

    val open: Collection<OpenDoc> get() = byPath.values

    /** Paths currently synced; changes after every open or close, so presenters can repaint. */
    val paths: StateFlow<Set<String>> = pathsFlow.asStateFlow()

    fun doc(path: String): OpenDoc? = byPath[path]

    fun context(path: String): DocContext? = byPath[path]?.let { DocContext(environmentId, projectId, it.uri, it.languageId) }

    fun docForUri(uri: String): OpenDoc? {
        val canonical = FileUri.canonical(uri)
        return byPath.values.firstOrNull { it.uri == canonical }
    }

    /** Current version the store holds for [path] (what a result must match to be fresh). */
    fun version(path: String): Int? = byPath[path]?.let { store.snapshot(it.uri)?.version }

    /** The text of [path] at [version], while it is recent enough to be remembered. */
    fun textAt(path: String, version: Int?): String? {
        val doc = byPath[path] ?: return null
        val snap = store.snapshot(doc.uri) ?: return null
        if (version == null || version == snap.version) return snap.text
        return synchronized(history) { history[doc.uri]?.firstOrNull { it.first == version }?.second }
    }

    /**
     * Reconciles with the tab list. Called for every workspace state change, off the main
     * thread (the language lookup may read an asset); unchanged tabs cost a map lookup and an
     * identity compare.
     *
     * @param enabled `lsp.enabled` for a language id (L scope): its documents close when off.
     */
    fun sync(tabs: List<EditorTab>, enabled: (languageId: String) -> Boolean) {
        val wanted = tabs.filter { it.editable && languageOf(it.name)?.let(enabled) == true }
        val keep = wanted.mapTo(HashSet()) { it.relativePath }
        for (path in byPath.keys - keep) close(path)
        for (tab in wanted) {
            val doc = byPath[tab.relativePath] ?: openTab(tab) ?: continue
            if (lastText[doc.path] !== tab.content) {
                lastText[doc.path] = tab.content
                client.changeDocument(environmentId, projectId, doc.uri, tab.content)
                remember(doc.uri)
            }
        }
        pathsFlow.value = byPath.keys.toSet()
    }

    /**
     * Pushes [text] for [path] now, ahead of the tab collector, when a request is about to be
     * made against exactly this text. A no-op when the store already holds it.
     */
    fun ensureCurrent(path: String, text: String) {
        val doc = byPath[path] ?: return
        client.changeDocument(environmentId, projectId, doc.uri, text)
        remember(doc.uri)
    }

    /** A successful write of [text]; servers get `didSave` (with text if they asked). */
    fun saved(path: String, text: String) {
        val doc = byPath[path] ?: return
        lastText[path] = text
        client.savedDocument(environmentId, projectId, doc.uri, text)
    }

    fun closeAll() {
        for (path in byPath.keys.toList()) close(path)
        pathsFlow.value = emptySet()
    }

    private fun openTab(tab: EditorTab): OpenDoc? {
        val languageId = languageOf(tab.name) ?: return null
        val uri = client.uriFor(environmentId, projectId, File(projectRoot, tab.relativePath)) ?: return null
        val doc = OpenDoc(tab.relativePath, FileUri.canonical(uri), languageId, tab.name)
        byPath[tab.relativePath] = doc
        lastText[tab.relativePath] = tab.content
        client.openDocument(DocContext(environmentId, projectId, doc.uri, languageId), tab.content)
        remember(doc.uri)
        return doc
    }

    private fun languageOf(fileName: String): String? =
        languageCache.getOrPut(fileName) { LanguageConfigs.languageIdFor(fileName) ?: NO_LANGUAGE }.takeIf { it != NO_LANGUAGE }

    private fun close(path: String) {
        val doc = byPath.remove(path) ?: return
        lastText.remove(path)
        synchronized(history) { history.remove(doc.uri) }
        client.closeDocument(environmentId, projectId, doc.uri)
    }

    private fun remember(uri: String) {
        val snap = store.snapshot(uri) ?: return
        synchronized(history) {
            val ring = history.getOrPut(uri) { ArrayDeque() }
            if (ring.lastOrNull()?.first == snap.version) return
            ring.addLast(snap.version to snap.text)
            while (ring.size > LspUiPolicy.TEXT_HISTORY_VERSIONS) ring.removeFirst()
        }
    }

    private companion object {
        /** Cache marker for files with no language id, so the lookup is not repeated. */
        const val NO_LANGUAGE = ""
    }
}
