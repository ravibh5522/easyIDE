package dev.easyide.app.ui.screens.workspace.edit

import dev.easyide.app.ui.screens.workspace.decor.OffsetEdit

/** Tunables of [EditHistory], named in one place (a test shrinks them; the app uses the defaults). */
data class HistoryLimits(
    /** Typing or deleting pauses longer than this start a new undo step. */
    val coalesceIdleMs: Long = COALESCE_IDLE_MS,
    /** Undo steps kept per document; the oldest are dropped first. */
    val maxSteps: Int = MAX_STEPS,
    /**
     * Characters held across all steps of one document (removed + inserted text, both stacks).
     * A step is a patch, not a snapshot, so this is what bounds memory: 2M chars is about 4 MB,
     * and one format of a maximum-size (256 KB) buffer costs roughly a quarter of it.
     */
    val maxRetainedChars: Int = MAX_RETAINED_CHARS,
) {
    companion object {
        const val COALESCE_IDLE_MS = 1_500L
        const val MAX_STEPS = 1_000
        const val MAX_RETAINED_CHARS = 2_000_000
    }
}

/**
 * The undo/redo history of one document. Pure Kotlin and engine-neutral (decision 0018): the
 * current text field, the LSP and extension edit paths and the future virtualised editor all
 * feed it the same `(before, after)` pairs and read back the [TextState] to show.
 *
 * Each step is a **patch** (`[start, start + removed.length)` became [Step.inserted]) plus the
 * selection before and after it, so memory follows what was edited rather than document size,
 * and undo/redo restore the caret and selection the user had.
 *
 * What makes an edit one step, decided from the edit alone (so auto-close, auto-indent, a
 * format, a rename apply or a snippet insert - each a single `before -> after` transition -
 * are one step by construction):
 *  - a run of single typed characters merges while the caret does not move away, the pause
 *    between keystrokes stays under [HistoryLimits.coalesceIdleMs], and the run does not
 *    start a new word after a non-word character (so undo removes "world", then "hello ");
 *  - a run of backspaces (or forward deletes) merges the same way;
 *  - anything else - a newline, a paste, a completion, an edit over a selection, a multi-char
 *    programmatic edit - is its own step and is sealed, so typing after it starts fresh.
 *
 * The model tracks [head], the text after the last recorded/applied step. An edit whose
 * `before` text differs from it means the buffer changed behind the history's back (a file
 * reloaded from disk): the history is dropped and restarts from that text rather than
 * applying patches to text they were not made against.
 *
 * Not thread-safe; [EditHistories] serialises access.
 */
class EditHistory(baseline: String, private val limits: HistoryLimits = HistoryLimits()) {

    private enum class Run { NONE, INSERT, DELETE_BACK, DELETE_FORWARD }

    private class Step(
        var start: Int,
        var removed: String,
        var inserted: String,
        val selBefore: Selection,
        var selAfter: Selection,
        var lastMs: Long,
        val run: Run,
        var sealed: Boolean,
    ) {
        val chars: Int get() = removed.length + inserted.length
    }

    private data class Selection(val start: Int, val end: Int)

    private val undoStack = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()
    private var retained = 0

    /** The text after the last recorded or applied step. */
    var head: String = baseline
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Drops every step and restarts from [text]. */
    fun reset(text: String) {
        undoStack.clear()
        redoStack.clear()
        retained = 0
        head = text
    }

    /**
     * Records the edit that took [before] to [after]. Returns false when nothing was recorded
     * (the texts are equal: a caret move is not an edit).
     */
    fun record(before: TextState, after: TextState, nowMs: Long): Boolean {
        if (before.text != head) reset(before.text)
        val edit = refinedEdit(before, after) ?: return false
        val step = Step(
            start = edit.start,
            removed = before.text.substring(edit.start, edit.oldEnd),
            inserted = after.text.substring(edit.start, edit.newEnd),
            selBefore = Selection(before.selectionStart, before.selectionEnd),
            selAfter = Selection(after.selectionStart, after.selectionEnd),
            lastMs = nowMs,
            run = classify(before, after, edit),
            sealed = false,
        )
        clearRedo()
        head = after.text
        val last = undoStack.lastOrNull()
        if (last != null && merge(last, step)) {
            retained += step.chars
        } else {
            undoStack.addLast(step)
            retained += step.chars
        }
        trim()
        return true
    }

    /**
     * The state one step back, with the selection from before that step; null when there is
     * nothing to undo. [current] must be the buffer the caller shows: if it is not [head]
     * the history is stale and restarts from it (nothing is undone).
     */
    fun undo(current: String): TextState? {
        if (current != head) { reset(current); return null }
        val step = undoStack.removeLastOrNull() ?: return null
        val text = head.replaceRange(step.start, step.start + step.inserted.length, step.removed)
        redoStack.addLast(step)
        undoStack.lastOrNull()?.sealed = true
        head = text
        return TextState(text, step.selBefore.start, step.selBefore.end)
    }

    /** The state one step forward, with the selection the step ended with; null when there is nothing to redo. */
    fun redo(current: String): TextState? {
        if (current != head) { reset(current); return null }
        val step = redoStack.removeLastOrNull() ?: return null
        val text = head.replaceRange(step.start, step.start + step.removed.length, step.inserted)
        step.sealed = true
        undoStack.addLast(step)
        head = text
        return TextState(text, step.selAfter.start, step.selAfter.end)
    }

    private fun clearRedo() {
        redoStack.forEach { retained -= it.chars }
        redoStack.clear()
    }

    /**
     * The single replacement from [before] to [after], placed where the user made it. The
     * prefix/suffix diff cannot tell typing "a" before or after an existing "a" apart; the
     * caret can, and the position decides whether the next keystroke continues the run.
     */
    private fun refinedEdit(before: TextState, after: TextState): OffsetEdit? {
        val diff = OffsetEdit.between(before.text, after.text) ?: return null
        val inserted = diff.newEnd - diff.start
        val removed = diff.oldEnd - diff.start
        val caret = after.selectionEnd
        if (after.collapsed && (removed == 0 || inserted == 0)) {
            val start = if (removed == 0) caret - inserted else caret
            val candidate = if (removed == 0) OffsetEdit(start, start, start + inserted) else OffsetEdit(start, start + removed, start)
            if (start >= 0 && candidate.newEnd <= after.text.length && candidate.oldEnd <= before.text.length && applies(before.text, after.text, candidate)) {
                return candidate
            }
        }
        return diff
    }

    private fun applies(old: String, new: String, e: OffsetEdit): Boolean =
        old.length - (e.oldEnd - e.start) + (e.newEnd - e.start) == new.length &&
            old.regionMatches(0, new, 0, e.start) &&
            old.regionMatches(e.oldEnd, new, e.newEnd, old.length - e.oldEnd)

    private fun classify(before: TextState, after: TextState, e: OffsetEdit): Run {
        val inserted = e.newEnd - e.start
        val removed = e.oldEnd - e.start
        val text = if (inserted > 0) after.text.substring(e.start, e.newEnd) else before.text.substring(e.start, e.oldEnd)
        if (!isSingleChar(text)) return Run.NONE
        return when {
            inserted > 0 && after.collapsed -> Run.INSERT
            removed > 0 && inserted == 0 && before.collapsed && after.collapsed -> when (before.selectionStart) {
                e.oldEnd -> Run.DELETE_BACK
                e.start -> Run.DELETE_FORWARD
                else -> Run.NONE
            }
            else -> Run.NONE
        }
    }

    private fun isSingleChar(s: String): Boolean =
        s.isNotEmpty() && s != "\n" && s != "\r" && s.codePointCount(0, s.length) == 1

    /** Folds [next] into [last] when they are one run; false leaves both as they are. */
    private fun merge(last: Step, next: Step): Boolean {
        if (last.sealed || next.run == Run.NONE || last.run != next.run) return false
        if (next.lastMs - last.lastMs > limits.coalesceIdleMs || next.selBefore != last.selAfter) return false
        when (next.run) {
            Run.INSERT -> {
                if (next.removed.isNotEmpty() || next.start != last.start + last.inserted.length) return false
                if (startsNewWord(last.inserted, next.inserted)) return false
                last.inserted += next.inserted
            }
            Run.DELETE_BACK -> {
                if (next.start + next.removed.length != last.start) return false
                last.removed = next.removed + last.removed
                last.start = next.start
            }
            Run.DELETE_FORWARD -> {
                if (next.start != last.start) return false
                last.removed += next.removed
            }
            Run.NONE -> return false
        }
        last.selAfter = next.selAfter
        last.lastMs = next.lastMs
        return true
    }

    private fun startsNewWord(previous: String, typed: String): Boolean =
        isWordChar(typed.first()) && !isWordChar(previous.last())

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private fun trim() {
        while (undoStack.size > 1 && (undoStack.size > limits.maxSteps || retained > limits.maxRetainedChars)) {
            retained -= undoStack.removeFirst().chars
        }
    }
}
