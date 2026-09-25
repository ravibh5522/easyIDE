package dev.easyide.app.ui.shell

internal fun uri(text: String): DocumentUri = requireNotNull(DocumentUri.parse(text)) { "malformed test URI $text" }

internal fun file(name: String): DocumentUri = requireNotNull(DocumentUri.file("/workspace/$name")) { name }

internal fun terminal(id: String): DocumentUri = requireNotNull(DocumentUri.terminal(id)) { id }

internal fun type(id: String, scheme: String, multiple: Boolean = false, supportsSplit: Boolean = true, restorable: Boolean = true) =
    DocumentType(id, UriPattern(scheme), { it.name }, { IconRef("doc") }, multiple, supportsSplit, restorable)

internal val FILE_TYPE = type("file", "file")
internal val TERMINAL_TYPE = type("terminal", "terminal", multiple = true)
internal val DIFF_TYPE = type("git-diff", "git-diff", supportsSplit = false)
internal val EPHEMERAL_TYPE = type("ephemeral", "preview", restorable = false)

internal fun typeOf(uri: DocumentUri): DocumentType = when (uri.scheme) {
    "file" -> FILE_TYPE
    "terminal" -> TERMINAL_TYPE
    "git-diff" -> DIFF_TYPE
    "preview" -> EPHEMERAL_TYPE
    else -> DocumentType.UNAVAILABLE
}

internal val ENV = ShellEnv(::typeOf)

/** A group with these files open in order, each as a normal tab. */
internal fun group(vararg names: String): EditorGroup =
    names.fold(EditorGroup()) { g, n -> g.open(file(n), preview = false) }
