package dev.easyide.sandbox.files

/**
 * Outcome of asking to open a file. Opening is a policy decision, not just a
 * read: a compiled object is not text, and a 40 MB log must not be loaded into
 * an editable buffer just because it happens to be text.
 */
sealed interface FileContent {

    /**
     * @param editable false when only a prefix was loaded - allowing edits to a
     *   partial buffer would silently truncate the file on save.
     * @param highlightingEnabled off for large files; colouring is O(size) and
     *   would run on every keystroke.
     * @param truncated true when [text] is a prefix of the file.
     */
    data class Text(
        val text: String,
        val editable: Boolean,
        val highlightingEnabled: Boolean,
        val truncated: Boolean,
        val totalBytes: Long,
    ) : FileContent

    /** Small non-text file, shown read-only as a hex dump. */
    data class BinaryPreview(
        val hexDump: String,
        val totalBytes: Long,
    ) : FileContent

    /** Nothing is opened; [reason] is shown to the user. */
    data class Rejected(val reason: String) : FileContent
}

/**
 * Size policy for opening files. Public so the UI can explain limits rather
 * than just refusing, and so the thresholds live in exactly one place.
 */
object FilePolicy {
    /** Bytes sniffed to decide text vs binary. */
    const val SNIFF_BYTES = 8 * 1024

    /**
     * Fully editable below this. Bounded by the editor widget, not by memory:
     * a single text field holding megabytes stalls layout on the UI thread, so
     * anything larger is shown through the virtualised read-only viewer.
     */
    const val TEXT_EDIT_MAX_BYTES = 256L * 1024

    /** Above TEXT_EDIT_MAX, opened read-only as a prefix, up to this size. */
    const val TEXT_VIEW_MAX_BYTES = 8L * 1024 * 1024

    /** How much of an oversized file the read-only viewer loads. */
    const val TEXT_VIEW_PREFIX_BYTES = 2L * 1024 * 1024

    /**
     * Syntax colouring is disabled above this, and the tab says so.
     *
     * Deliberately equal to [TEXT_EDIT_MAX_BYTES]: **anything the editor will
     * let you edit, it will colour.** That only became affordable once the
     * tokenizer stopped costing O(size) per change - it now keeps per-line state
     * so an edit re-scans from the changed line, and only styles the lines in
     * the viewport, so cost tracks what is on screen rather than file length.
     *
     * The binding limit above this is the text field itself, not colouring:
     * a single field holding more than this stalls layout regardless of spans,
     * which is what [TEXT_EDIT_MAX_BYTES] already encodes.
     */
    const val HIGHLIGHT_MAX_BYTES = TEXT_EDIT_MAX_BYTES

    /** Binaries above this are refused outright rather than hex-dumped. */
    const val BINARY_PREVIEW_MAX_BYTES = 128L * 1024

    fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
