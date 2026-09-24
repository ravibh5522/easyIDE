package dev.easyide.app.ui.screens.workspace.lsp

import dev.easyide.app.ui.commands.fuzzyScore
import dev.easyide.app.ui.screens.workspace.edit.TextState
import dev.easyide.lsp.protocol.CompletionEdit
import dev.easyide.lsp.protocol.CompletionItem
import dev.easyide.lsp.protocol.InsertTextFormat
import dev.easyide.lsp.session.ServerKey
import dev.easyide.lsp.text.LineIndex

/** One completion item with the server that produced it, so resolve and commands go back there. */
data class CompletionEntry(val server: ServerKey, val item: CompletionItem, val resolved: Boolean = false)

/** How an item was accepted: Enter uses an `InsertReplaceEdit`'s insert range, Tab its replace range. */
enum class AcceptMode { INSERT, REPLACE }

/**
 * The document as it was when completion was requested. Items' ranges refer to this text;
 * everything typed since then is inside `[wordStart, caret)` of the current text, which is
 * what [CompletionModel.canRefilter] guarantees before any item is reused.
 */
data class CompletionOrigin(val text: String, val caret: Int, val wordStart: Int) {
    val lines: LineIndex by lazy { LineIndex(text) }
}

/** An accepted completion: the new buffer, where the caret goes, and snippet fields if any. */
data class Acceptance(val text: String, val selection: OpenRange, val snippet: SnippetSession?)

/**
 * Pure completion logic (lsp-features.md 4.2): the word at the caret, local fuzzy filtering and
 * sorting, whether a response can be reused after more typing, and how an item turns into an
 * edit of the current text. Everything here is a unit test; the controller only sequences it.
 */
object CompletionModel {

    /** The one character typed at a collapsed caret, or null for anything else (paste, delete, IME swap). */
    fun typedChar(before: TextState, after: TextState): Char? =
        insertedText(before, after)?.singleOrNull()?.takeIf { after.selectionEnd == before.max + 1 }

    /** Text inserted at a collapsed caret with nothing removed, or null. */
    fun insertedText(before: TextState, after: TextState): String? {
        if (!before.collapsed) return null
        val at = before.max
        val added = after.text.length - before.text.length
        if (added <= 0) return null
        if (!after.text.regionMatches(0, before.text, 0, at)) return null
        if (!after.text.regionMatches(at + added, before.text, at, before.text.length - at)) return null
        return after.text.substring(at, at + added)
    }

    /** The row selected when a list opens: the first `preselect` item, else the top one. */
    fun initialSelection(entries: List<CompletionEntry>): Int = entries.indexOfFirst { it.item.preselect }.coerceAtLeast(0)

    /** Identifier characters, the default `wordPattern` of VS Code for most languages. */
    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    /** Start of the identifier that ends at [caret]. */
    fun wordStart(text: String, caret: Int): Int {
        var i = caret.coerceIn(0, text.length)
        while (i > 0 && isWordChar(text[i - 1])) i--
        return i
    }

    /**
     * Items matching [word] (fuzzy on `filterText`), in server `sortText` order, then match
     * score, then label; at most [perSource] per server so one huge list cannot starve another.
     * An empty word keeps every item.
     */
    fun filter(entries: List<CompletionEntry>, word: String, perSource: Int): List<CompletionEntry> {
        val scored = entries.mapNotNull { e ->
            val score = if (word.isEmpty()) 0 else fuzzyScore(word, e.item.filterText) ?: return@mapNotNull null
            e to score
        }
        val sorted = scored.sortedWith(
            compareBy<Pair<CompletionEntry, Int>> { it.first.item.sortText }
                .thenByDescending { it.second }
                .thenBy { it.first.item.label },
        )
        val taken = HashMap<ServerKey, Int>()
        return sorted.mapNotNull { (e, _) ->
            val n = taken.merge(e.server, 1, Int::plus) ?: 1
            e.takeIf { n <= perSource }
        }
    }

    /**
     * Whether items computed for [origin] still apply to [text] with the caret at [caret]:
     * only the current word changed (typed or deleted), nothing before the word or after the
     * old caret did. The common case while typing, and it avoids a request per keystroke.
     */
    fun canRefilter(origin: CompletionOrigin, text: String, caret: Int): Boolean {
        if (caret < origin.wordStart) return false
        val tail = origin.text.length - origin.caret
        if (text.length - caret != tail) return false
        if (!text.regionMatches(0, origin.text, 0, origin.wordStart)) return false
        if (!text.regionMatches(caret, origin.text, origin.caret, tail)) return false
        return (origin.wordStart until caret).all { isWordChar(text[it]) }
    }

    /**
     * Applies [entry] to [text] (caret at [caret]), where [text] is [origin] with only the
     * word edited (see [canRefilter]). The main edit and `additionalTextEdits` become one
     * change of the buffer - one undo unit - and a snippet's fields are returned for Tab.
     *
     * @return null when the item's edits overlap each other, which the protocol forbids.
     */
    fun accept(
        entry: CompletionEntry,
        origin: CompletionOrigin,
        text: String,
        caret: Int,
        mode: AcceptMode,
        indent: String,
        tab: String,
        variable: (String) -> String?,
    ): Acceptance? {
        val item = entry.item
        val delta = text.length - origin.text.length
        // What was typed since the request sits at the origin caret, so a range ending there now
        // ends at the caret, and one starting there still starts there (it covers the typing).
        fun mapStart(offset: Int) = if (offset > origin.caret) offset + delta else minOf(offset, caret)
        fun mapEnd(offset: Int) = if (offset >= origin.caret) offset + delta else offset
        val (start, end) = when (val edit = item.edit) {
            is CompletionEdit.Replace -> origin.lines.offset(edit.edit.range.start) to origin.lines.offset(edit.edit.range.end)
            is CompletionEdit.InsertReplace -> {
                val r = if (mode == AcceptMode.INSERT) edit.insert else edit.replace
                origin.lines.offset(r.start) to origin.lines.offset(r.end)
            }
            null -> origin.wordStart to origin.caret
        }
        val insertText = item.edit?.newText ?: item.insertText
        val snippet = if (item.insertTextFormat == InsertTextFormat.SNIPPET) {
            SnippetParser.parse(insertText, indent, tab, variable)
        } else {
            SnippetText(insertText, emptyList())
        }
        val main = Replacement(mapStart(start), mapEnd(end), snippet.text)
        val extra = item.additionalTextEdits.map { e ->
            Replacement(mapStart(origin.lines.offset(e.range.start)), mapEnd(origin.lines.offset(e.range.end)), e.newText)
        }
        val applied = Replacement.applyAll(text, extra + main) ?: return null
        val at = applied.shiftBefore(main)
        val session = SnippetSession.start(snippet, at)
        val selection = session?.active ?: (at + snippet.finalOffset).let { OpenRange(it, it) }
        return Acceptance(applied.text, selection, session)
    }

    /**
     * Whether Enter should accept under `editor.acceptSuggestionOnEnter: smart`: only when the
     * insertion is more than the word already typed (VS Code: "only when it makes a change").
     */
    fun makesTextualChange(entry: CompletionEntry, typedWord: String): Boolean {
        val insert = entry.item.edit?.newText ?: entry.item.insertText
        return insert != typedWord
    }
}

/** One replacement of `[start, end)` by [text] in a buffer. */
data class Replacement(val start: Int, val end: Int, val text: String) {
    val delta: Int get() = text.length - (end - start)

    /** [text] after applying every replacement; [shiftBefore] maps a replacement's new start. */
    class Applied(val text: String, private val all: List<Replacement>) {
        /** Where [r]'s start lands: moved by every replacement entirely before it. */
        fun shiftBefore(r: Replacement): Int = r.start + all.filter { it !== r && it.end <= r.start }.sumOf { it.delta }
    }

    companion object {
        /** Applies non-overlapping [edits] (any order) to [text]; null if two overlap. */
        fun applyAll(text: String, edits: List<Replacement>): Applied? {
            val sorted = edits.sortedWith(compareBy<Replacement> { it.start }.thenBy { it.end })
            for (k in 1 until sorted.size) if (sorted[k].start < sorted[k - 1].end) return null
            if (sorted.any { it.start < 0 || it.end > text.length || it.start > it.end }) return null
            val out = StringBuilder(text.length + sorted.sumOf { it.text.length })
            var at = 0
            for (r in sorted) {
                out.append(text, at, r.start).append(r.text)
                at = r.end
            }
            out.append(text, at, text.length)
            return Applied(out.toString(), sorted)
        }
    }
}
