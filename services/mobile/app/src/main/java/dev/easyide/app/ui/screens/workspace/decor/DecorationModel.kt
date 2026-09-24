package dev.easyide.app.ui.screens.workspace.decor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * The decorations of one open document.
 *
 * Every write names the text its offsets were computed against, and the model first moves
 * what it holds onto that text. So the order in which edits and results arrive does not
 * matter: a result positioned against a newer buffer than the model last saw carries the
 * model forward, and a keystroke carries every earlier result with it. Results computed
 * against an *older* version are the producer's job to shift first (lsp-features.md 3.3,
 * `DecorationShifter` in `:lsp`), since only the producer knows which edits came between.
 *
 * Writes are atomic compare-and-set updates of one immutable [DecorationSet], so producers
 * on any thread cannot tear a snapshot; the editor reads [state] on the main thread.
 */
class DecorationModel(initialText: String = "") {

    private val mutableState = MutableStateFlow(DecorationSet.empty(initialText))

    /** The latest snapshot; a new instance on every change, the same one otherwise. */
    val state: StateFlow<DecorationSet> = mutableState.asStateFlow()

    /** Moves every decoration onto [text]. Called for each buffer change; cheap when equal. */
    fun syncText(text: String) = mutableState.update { it.rebased(text) }

    /**
     * Replaces [source]'s decorations in [layer] with [items], whose offsets refer to [text].
     * An empty list clears them.
     */
    fun <T : Decoration> set(layer: DecorationLayer<T>, source: String, items: List<T>, text: String) =
        mutableState.update { it.rebased(text).with(layer, source, items) }

    fun clear(layer: DecorationLayer<*>, source: String) = mutableState.update { it.without(layer, source) }

    /** Removes everything [source] contributed, e.g. when its language server stops. */
    fun clearSource(source: String) = mutableState.update { it.withoutSource(source) }
}

/**
 * One [DecorationModel] per open document path, owned by the workspace so decorations
 * outlive tab switches (only the active tab's editor is composed) and producers can write
 * to documents that are open but not visible.
 */
class DecorationRegistry {

    private val models = ConcurrentHashMap<String, DecorationModel>()

    /** The model for [path], created empty on first use; its first sync adopts the buffer. */
    fun model(path: String): DecorationModel = models.computeIfAbsent(path) { DecorationModel() }

    /** Drops [path]'s model; called when its tab closes. */
    fun remove(path: String) {
        models.remove(path)
    }

    /** Keeps [from]'s decorations under [to] after a rename; the text did not change. */
    fun rename(from: String, to: String) {
        models.remove(from)?.let { models[to] = it }
    }
}
