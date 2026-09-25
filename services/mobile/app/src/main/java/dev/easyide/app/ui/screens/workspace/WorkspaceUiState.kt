package dev.easyide.app.ui.screens.workspace

import dev.easyide.app.session.ExternalState
import dev.easyide.sandbox.files.FileNode

/**
 * An open editor tab. [savedContent] is what is on disk; [content] is the
 * buffer. A tab may be read-only (a binary preview, or a truncated view of a
 * file too large to edit safely), in which case it can never be dirty.
 */
data class EditorTab(
    val relativePath: String,
    val name: String,
    val content: String,
    val savedContent: String,
    val editable: Boolean = true,
    val highlightingEnabled: Boolean = true,
    val notice: String? = null,
    val showPreview: Boolean = false,
    /** Set by the external change check (S8) when the file no longer matches what this buffer was based on. */
    val externalState: ExternalState = ExternalState.InSync,
) {
    /**
     * Cached per instance: it is read by the tab bar, status bar and screen on
     * every recomposition, and a same-length edit makes the compare O(n).
     */
    val isDirty: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) { editable && content != savedContent }

    /** Markdown gets a preview toggle; nothing else has a renderer yet. */
    val isMarkdown: Boolean get() = name.substringAfterLast('.', "").lowercase() in MARKDOWN_EXTENSIONS

    private companion object {
        val MARKDOWN_EXTENSIONS = setOf("md", "markdown")
    }
}

/** A pending file copy/cut, populated by the explorer context menu. */
data class FileClipboard(val relativePath: String, val isCut: Boolean)

data class WorkspaceUiState(
    val projectName: String = "",
    val tree: List<FileNode> = emptyList(),
    val expandedDirs: Set<String> = emptySet(),
    val childrenByDir: Map<String, List<FileNode>> = emptyMap(),
    val openTabs: List<EditorTab> = emptyList(),
    val activeTabPath: String? = null,
    val terminals: List<PtyTerminalTab> = emptyList(),
    val activeTerminalId: String? = null,
    val statusMessage: String? = null,
    val linuxReady: Boolean = false,
    val isInstalling: Boolean = false,
    val clipboard: FileClipboard? = null,
    /** Bumped when something asks for the terminal stage to show (an install run). */
    val terminalRevealRequests: Int = 0,
) {
    val activeTab: EditorTab? get() = openTabs.find { it.relativePath == activeTabPath }
    val activeTerminal: PtyTerminalTab? get() = terminals.find { it.id == activeTerminalId }
}
