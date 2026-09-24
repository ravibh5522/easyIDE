package dev.easyide.app.extensions

/**
 * App-side extension limits with no sdk-reference settings key (R-ENG-07: one table).
 * Values are starting points, to be profiled like the runtime's own ExtensionPolicy.
 */
object ExtensionUiPolicy {
    /** Extension Log ring size; sdk-reference names 2000 lines for the shared log ring. */
    const val LOG_RING_ENTRIES = 2_000

    /** Most editor/title actions shown as icons before the rest move to the overflow menu. */
    const val EDITOR_TITLE_MAX_INLINE = 3

    /** Snippet files are small; a larger one is refused rather than parsed on the UI path. */
    const val SNIPPET_FILE_MAX_BYTES = 1L * 1024 * 1024

    /** `.easyide/tasks.json` size bound (same order as settings files). */
    const val TASKS_FILE_MAX_BYTES = 1L * 1024 * 1024

    /** Copy buffer for staging packages from SAF. */
    const val COPY_BUFFER_BYTES = 64 * 1024

    /** Directory depth bound while copying a picked folder (a cycle-proof walk). */
    const val MAX_FOLDER_DEPTH = 32
}
