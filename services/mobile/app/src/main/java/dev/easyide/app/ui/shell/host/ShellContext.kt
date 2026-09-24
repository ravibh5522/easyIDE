package dev.easyide.app.ui.shell.host

import androidx.compose.runtime.staticCompositionLocalOf
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.ShellState

/**
 * What a panel or document may ask of the shell it is drawn in: open a document, go to a
 * navigation destination, step back, show a short message. Renderers read it instead of taking view
 * models, so the same composable works in any host.
 */
class ShellActions(val open: (DocumentUri) -> Unit, val goTo: (String) -> Unit, val back: () -> Unit, val notify: (String) -> Unit)

val LocalShellActions = staticCompositionLocalOf<ShellActions> { error("no shell around this composable") }

/** The current shell state, for renderers that show a selection (the open settings category, the open project). */
val LocalShellState = staticCompositionLocalOf<ShellState> { error("no shell around this composable") }

/** The document the current scope's active group shows, or null while the stage is empty. */
val ShellState.activeDocument: DocumentUri? get() = current.stage.activeGroup.activeTab?.uri
