package dev.easyide.app.ui.shell

/**
 * One editor group's back/forward history, as a browser keeps it (shell-model.md section 4.2):
 * every time the group's active location changes the old one is pushed on [back]; going back moves
 * the current one onto [forward]. Locations are full URIs, so a sub-page change inside one tab
 * (`settings/editor` to `settings/editor#fonts`) is a step too. Newest entry is last.
 */
data class NavHistory(val back: List<DocumentUri> = emptyList(), val forward: List<DocumentUri> = emptyList()) {

    val canGoBack: Boolean get() = back.isNotEmpty()
    val canGoForward: Boolean get() = forward.isNotEmpty()

    /** Records that the group left [from] for somewhere new: a fresh branch, so forward is dropped. */
    fun push(from: DocumentUri): NavHistory {
        if (back.lastOrNull() == from) return copy(forward = emptyList())
        return NavHistory((back + from).takeLast(ShellLimits.HISTORY_LIMIT), emptyList())
    }

    /** The location to go back to and the history after doing so, skipping entries whose tab is gone. */
    fun stepBack(current: DocumentUri, isOpen: (DocumentUri) -> Boolean): Pair<DocumentUri, NavHistory>? {
        val i = back.indexOfLast { it != current && isOpen(it.key) }
        if (i < 0) return null
        return back[i] to NavHistory(back.take(i), (forward + current).takeLast(ShellLimits.HISTORY_LIMIT))
    }

    fun stepForward(current: DocumentUri, isOpen: (DocumentUri) -> Boolean): Pair<DocumentUri, NavHistory>? {
        val i = forward.indexOfLast { it != current && isOpen(it.key) }
        if (i < 0) return null
        return forward[i] to NavHistory((back + current).takeLast(ShellLimits.HISTORY_LIMIT), forward.take(i))
    }

    /** Forgets every location of a tab that was closed. */
    fun without(key: DocumentUri): NavHistory =
        NavHistory(back.filter { it.key != key }, forward.filter { it.key != key })

    companion object {
        val EMPTY = NavHistory()
    }
}
