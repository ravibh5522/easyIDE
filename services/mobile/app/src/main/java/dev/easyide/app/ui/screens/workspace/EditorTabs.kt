package dev.easyide.app.ui.screens.workspace

import dev.easyide.sandbox.files.FileContent
import dev.easyide.sandbox.files.FileNode
import dev.easyide.sandbox.files.FilePolicy

/**
 * The tab a freshly read file opens as, or null when the file is refused (the reason goes to
 * [onRejected]). One mapping for every way a tab is created from disk: opening from the
 * explorer, and restoring a saved session.
 */
internal fun editorTabFor(node: FileNode, content: FileContent, onRejected: (String) -> Unit): EditorTab? = when (content) {
    is FileContent.Rejected -> {
        onRejected(content.reason)
        null
    }

    is FileContent.BinaryPreview -> EditorTab(
        relativePath = node.relativePath,
        name = node.name,
        content = content.hexDump,
        savedContent = content.hexDump,
        editable = false,
        highlightingEnabled = false,
        notice = "Binary (${FilePolicy.humanSize(content.totalBytes)}) - read-only preview",
    )

    is FileContent.Text -> EditorTab(
        relativePath = node.relativePath,
        name = node.name,
        content = content.text,
        savedContent = content.text,
        editable = content.editable,
        highlightingEnabled = content.highlightingEnabled,
        notice = textNotice(content),
        // Markdown opens in preview, matching how it is usually read.
        showPreview = node.name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown"),
    )
}

private fun textNotice(content: FileContent.Text): String? = when {
    content.truncated -> "First ${FilePolicy.humanSize(FilePolicy.TEXT_VIEW_PREFIX_BYTES)} " +
        "of ${FilePolicy.humanSize(content.totalBytes)} - read-only"

    !content.editable -> "${FilePolicy.humanSize(content.totalBytes)} - read-only"
    !content.highlightingEnabled -> "Large file - syntax highlighting off"
    else -> null
}
