package dev.easyide.app.ui.screens.workspace.ext

/**
 * The editor-buffer operations extension actions need from the workspace (implemented by
 * `WorkspaceViewModel`). Paths are project-relative here; guest paths are converted by
 * [WorkspaceExtensionHost].
 */
interface EditorBuffers {
    /** Opens (or switches to) [relativePath]; true when its tab is open afterwards. */
    suspend fun openAndAwait(relativePath: String): Boolean

    /** Replaces an open, editable tab's buffer; false when it is not open or read-only. */
    fun replaceContent(path: String, content: String): Boolean

    /** Writes a file that has no open tab; false when the write failed. */
    suspend fun writeClosedFile(path: String, content: String): Boolean

    /** Text of a file with no open tab; null when missing, binary or too large to edit. */
    suspend fun readClosedFile(path: String): String?
}
