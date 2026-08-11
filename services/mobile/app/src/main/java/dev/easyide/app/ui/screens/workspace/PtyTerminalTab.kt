package dev.easyide.app.ui.screens.workspace

import com.termux.terminal.TerminalSession

/**
 * One terminal tab: an id for the tab bar, a display title, and the real
 * pty-backed session. `TerminalSession`/`TerminalView` (from the vendored
 * `terminal-emulator`/`terminal-view` libraries) own the scrollback, cursor,
 * input handling and shell process entirely - there is nothing left here to
 * mirror into Compose state, unlike the earlier line-list terminal where the
 * ViewModel had to track every line, the prompt buffer and command history
 * itself.
 *
 * [title] is separate from `session.title` because it changes via a
 * callback ([com.termux.terminal.TerminalSessionClient.onTitleChanged]) that
 * has to reach Compose recomposition somehow; holding it here, updated by
 * that callback, is that path.
 */
data class PtyTerminalTab(
    val id: String,
    val title: String,
    val session: TerminalSession,
    /** The session's `TerminalSessionClient` - exposed so the currently
     * attached `TerminalView` (if any) can wire itself up to redraw on
     * screen updates. See [EasyTerminalSessionClient.onScreenChanged]. */
    val client: EasyTerminalSessionClient,
)
