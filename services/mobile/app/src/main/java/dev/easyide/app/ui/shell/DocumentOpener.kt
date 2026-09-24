package dev.easyide.app.ui.shell

/**
 * How a list row puts its document on the stage (shell-model.md section 4.1): a tap previews it, a double
 * tap keeps it open, and the row's menu sends it to the side. One class so every list that opens
 * documents (the change list, a commit's files) behaves the same, whatever it is wired to.
 */
class DocumentOpener(
    private val open: (DocumentUri, OpenOptions) -> Unit,
    private val beside: (DocumentUri) -> Unit,
) {
    fun preview(uri: DocumentUri) = open(uri, OpenOptions(preview = true))

    fun keep(uri: DocumentUri) = open(uri, OpenOptions(preview = false))

    fun beside(uri: DocumentUri) = beside.invoke(uri)
}
