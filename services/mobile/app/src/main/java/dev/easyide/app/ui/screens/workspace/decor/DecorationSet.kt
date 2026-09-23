package dev.easyide.app.ui.screens.workspace.decor

/**
 * Decorations of one layer from one source, sorted by start.
 *
 * [maxSpan] is the longest `end - start` in [items]. With it a range query can binary-search
 * for `start >= from - maxSpan` instead of scanning from the top: a multi-line diagnostic
 * that starts above the viewport is still found, and a file with thousands of search matches
 * costs O(log n + visible) per paint.
 */
internal class Spans<T : Decoration>(val items: List<T>, val maxSpan: Int) {

    /** Items touching the closed interval `[from, to]`, in start order. */
    fun inRange(from: Int, to: Int, out: MutableList<T>) {
        var low = 0
        var high = items.size
        val floor = from - maxSpan
        while (low < high) {
            val mid = (low + high) ushr 1
            if (items[mid].start < floor) low = mid + 1 else high = mid
        }
        for (index in low until items.size) {
            val item = items[index]
            if (item.start > to) break
            if (item.end >= from) out += item
        }
    }

    companion object {
        fun <T : Decoration> of(items: List<T>): Spans<T> =
            Spans(items, items.maxOfOrNull { it.end - it.start } ?: 0)
    }
}

/**
 * An immutable snapshot of every decoration on one document, together with the exact [text]
 * their offsets refer to.
 *
 * Keeping the text in the snapshot is what makes offsets trustworthy: a painter compares it
 * with the text it laid out and skips a frame rather than drawing a squiggle one edit out of
 * place, and [rebased] can always derive the edit from the text it was positioned against.
 *
 * Decorations are kept per (layer, source) so independent producers - several language
 * servers, find, an extension's `editor.decorate` - replace only their own entries.
 */
class DecorationSet private constructor(
    val text: String,
    private val layers: Map<DecorationLayer<*>, Map<String, Spans<*>>>,
) {
    val isEmpty: Boolean get() = layers.isEmpty()

    /** Every decoration in [layer] across sources, in start order. */
    fun <T : Decoration> items(layer: DecorationLayer<T>): List<T> = inRange(layer, 0, text.length)

    /**
     * Decorations in [layer] touching the closed offset interval `[from, to]`, in start order.
     * Closed so a point decoration at the end of the window (an inlay hint after the last
     * visible character) is included.
     */
    fun <T : Decoration> inRange(layer: DecorationLayer<T>, from: Int, to: Int): List<T> {
        val sources = layers[layer] ?: return emptyList()
        val out = ArrayList<T>()
        for (spans in sources.values) {
            @Suppress("UNCHECKED_CAST") // with() only ever stores Spans<T> under DecorationLayer<T>.
            (spans as Spans<T>).inRange(from, to, out)
        }
        if (sources.size > 1) out.sortBy { it.start }
        return out
    }

    /** Sources currently contributing to [layer]. */
    fun sources(layer: DecorationLayer<*>): Set<String> = layers[layer]?.keys.orEmpty()

    /**
     * This snapshot with [source]'s entries in [layer] replaced by [items].
     *
     * [items] come from outside the editor (a language server, an extension), so anything
     * outside `[0, text.length]` or with `start > end` is dropped here, at the boundary,
     * instead of reaching the painter.
     */
    internal fun <T : Decoration> with(layer: DecorationLayer<T>, source: String, items: List<T>): DecorationSet {
        val valid = items.filter { it.start in 0..it.end && it.end <= text.length }.sortedBy { it.start }
        if (valid.isEmpty()) return without(layer, source)
        val sources = layers[layer].orEmpty() + (source to Spans.of(valid))
        return DecorationSet(text, layers + (layer to sources))
    }

    internal fun without(layer: DecorationLayer<*>, source: String): DecorationSet {
        val sources = layers[layer] ?: return this
        if (source !in sources) return this
        val remaining = sources - source
        return DecorationSet(text, if (remaining.isEmpty()) layers - layer else layers + (layer to remaining))
    }

    internal fun withoutSource(source: String): DecorationSet {
        if (layers.values.none { source in it }) return this
        val kept = layers.mapValues { (_, sources) -> sources - source }.filterValues { it.isNotEmpty() }
        return DecorationSet(text, kept)
    }

    /**
     * This snapshot moved onto [newText]: the single edit between the two texts is derived
     * and every decoration is shifted, clipped or dropped per its layer's [EditPolicy].
     * O(unchanged text ends + decorations); returns `this` when nothing changed.
     */
    internal fun rebased(newText: String): DecorationSet {
        if (newText === text) return this
        val edit = OffsetEdit.between(text, newText) ?: return DecorationSet(newText, layers)
        if (layers.isEmpty()) return DecorationSet(newText, layers)
        val shifted = HashMap<DecorationLayer<*>, Map<String, Spans<*>>>(layers.size)
        for ((layer, sources) in layers) {
            val moved = HashMap<String, Spans<*>>(sources.size)
            for ((source, spans) in sources) {
                @Suppress("UNCHECKED_CAST") // Same invariant as inRange().
                shift(layer as DecorationLayer<Decoration>, spans as Spans<Decoration>, edit)?.let { moved[source] = it }
            }
            if (moved.isNotEmpty()) shifted[layer] = moved
        }
        return DecorationSet(newText, shifted)
    }

    companion object {
        fun empty(text: String): DecorationSet = DecorationSet(text, emptyMap())

        /** [spans] after [edit], or null when nothing survived. Order is kept: both maps are monotone. */
        private fun <T : Decoration> shift(layer: DecorationLayer<T>, spans: Spans<T>, edit: OffsetEdit): Spans<T>? {
            val out = ArrayList<T>(spans.items.size)
            for (item in spans.items) moved(layer, item, edit)?.let { out += it }
            return if (out.isEmpty()) null else Spans.of(out)
        }

        internal fun <T : Decoration> moved(layer: DecorationLayer<T>, item: T, edit: OffsetEdit): T? {
            if (item.end < edit.start) return item
            if (layer.onEdit == EditPolicy.DROP_ON_OVERLAP && edit.overlaps(item.start, item.end)) return null
            val point = item.start == item.end
            val start = edit.mapAfter(item.start)
            val end = if (point) start else edit.mapBefore(item.end)
            // A range whose whole text was replaced has nothing left to mark.
            if (!point && end <= start) return null
            return if (start == item.start && end == item.end) item else layer.moved(item, start, end)
        }
    }
}
