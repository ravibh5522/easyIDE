package dev.easyide.app.ui.shell.ext

import dev.easyide.app.extensions.host.DocumentOpener
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.GroupTarget
import dev.easyide.app.ui.shell.OpenOptions
import dev.easyide.extensions.action.OpenGroup

/**
 * The shell's side of `openDocument`: turns an extension's request (a URI text, a group, preview or not) into the shell's own
 * open. [open] is the scope's, the app's or the open workspace's. A URI that is not well formed is refused, so a pack cannot make the
 * shell open anything it could not have named itself.
 */
fun documentOpener(open: (DocumentUri, OpenOptions) -> Unit): DocumentOpener = DocumentOpener { uri, group, preview ->
    val parsed = DocumentUri.parse(uri)
    if (parsed != null) open(parsed, OpenOptions(group = groupOf(group), preview = preview))
    parsed != null
}

internal fun groupOf(group: OpenGroup): GroupTarget = when (group) {
    OpenGroup.ACTIVE -> GroupTarget.ACTIVE
    OpenGroup.BESIDE -> GroupTarget.BESIDE
    OpenGroup.NEW -> GroupTarget.NEW
}
