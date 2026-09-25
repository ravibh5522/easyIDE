package dev.easyide.app.ui.shell

import dev.easyide.app.ui.screens.workspace.layout.CloseScope
import dev.easyide.app.ui.screens.workspace.layout.TabOrder

/** PREVIEW is the italic single-tap tab the next preview replaces; PINNED tabs sit first and survive bulk closes. */
enum class TabState { PREVIEW, KEPT, PINNED }

data class Tab(val uri: DocumentUri, val state: TabState = TabState.KEPT) {
    /** Which document the tab shows; [uri] may additionally carry a sub-page. */
    val key: DocumentUri get() = uri.key
}

/**
 * An ordered strip of document tabs with one active, most-recently-used order, and a back/forward
 * history (shell-model.md sections 4.1, 4.2). The invariants are checked on construction, so an
 * inconsistent group cannot be built: keys are unique, at most one tab is a preview, pinned tabs
 * form a prefix, [active] is a tab (or null exactly when empty), and [mru] holds every tab key.
 *
 * Operations return a new group; a group never loses a document unless asked to close it.
 */
data class EditorGroup(
    val tabs: List<Tab> = emptyList(),
    val active: DocumentUri? = null,
    /** Tab keys, most recently active first. */
    val mru: List<DocumentUri> = emptyList(),
    val history: NavHistory = NavHistory.EMPTY,
) {
    init {
        val keys = tabs.map { it.key }
        require(keys.toSet().size == keys.size) { "duplicate tab" }
        require(tabs.count { it.state == TabState.PREVIEW } <= 1) { "more than one preview tab" }
        require(tabs.dropWhile { it.state == TabState.PINNED }.none { it.state == TabState.PINNED }) { "pinned tabs must come first" }
        require(active == null || active in keys) { "active must be a tab" }
        require((active == null) == tabs.isEmpty()) { "a group with tabs has an active one" }
        require(mru.size == keys.size && mru.toSet() == keys.toSet()) { "mru must list every tab once" }
    }

    val keys: List<DocumentUri> get() = tabs.map { it.key }
    val activeTab: Tab? get() = tabs.firstOrNull { it.key == active }
    val isEmpty: Boolean get() = tabs.isEmpty()
    private val pinnedCount: Int get() = tabs.count { it.state == TabState.PINNED }

    operator fun contains(key: DocumentUri): Boolean = tabs.any { it.key == key }

    fun tab(key: DocumentUri): Tab? = tabs.firstOrNull { it.key == key }

    /**
     * Shows [uri] in this group. A document already here is focused (a different sub-page just
     * navigates it); a new one becomes a tab, replacing the current preview when [preview]. Opening
     * a preview tab as a normal one keeps it. Without [focus] the active tab stays put, except in an
     * empty group where the new tab has to be the active one.
     */
    fun open(uri: DocumentUri, preview: Boolean, focus: Boolean = true): EditorGroup {
        val existing = tab(uri.key)
        if (existing == null) return addTab(Tab(uri, if (preview) TabState.PREVIEW else TabState.KEPT), focus)
        val kept = if (!preview && existing.state == TabState.PREVIEW) setState(uri.key, TabState.KEPT) else this
        return if (focus) kept.navigate(uri) else kept
    }

    /** Puts a tab taken from another group here; a document already here wins, a second preview becomes a normal tab. */
    fun insert(tab: Tab, focus: Boolean): EditorGroup {
        if (tab.key in this) return if (focus) navigate(tab.uri) else this
        val demoted = if (tab.state == TabState.PREVIEW && tabs.any { it.state == TabState.PREVIEW }) tab.copy(state = TabState.KEPT) else tab
        return addTab(demoted, focus)
    }

    private fun addTab(tab: Tab, focus: Boolean): EditorGroup {
        val old = if (tab.state == TabState.PREVIEW) tabs.firstOrNull { it.state == TabState.PREVIEW } else null
        val at = when {
            old != null -> tabs.indexOf(old)
            tab.state == TabState.PINNED -> pinnedCount
            else -> tabs.size
        }
        val base = if (old != null) close(old.key) else this
        val grown = base.copy(
            tabs = base.tabs.toMutableList().apply { add(at, tab) },
            mru = base.mru + tab.key,
            active = base.active ?: tab.key,
        )
        return if (focus) grown.navigate(tab.uri) else grown
    }

    /** Makes [uri]'s tab active with [uri] as its location, recording the location left behind. */
    private fun navigate(uri: DocumentUri): EditorGroup {
        val from = activeTab?.uri
        if (from == uri) return this
        val moved = tabs.map { if (it.key == uri.key) it.copy(uri = uri) else it }
        return copy(
            tabs = moved,
            active = uri.key,
            mru = listOf(uri.key) + mru.filter { it != uri.key },
            history = if (from != null) history.push(from) else history,
        )
    }

    /** Activates an open tab without touching its location. */
    fun activate(key: DocumentUri): EditorGroup = tab(key)?.let { navigate(it.uri) } ?: this

    fun close(key: DocumentUri): EditorGroup {
        if (key !in this) return this
        val rest = mru.filter { it != key }
        return copy(
            tabs = tabs.filter { it.key != key },
            mru = rest,
            active = if (active == key) rest.firstOrNull() else active,
            history = history.without(key),
        )
    }

    /**
     * The tabs [scope] selects relative to [key], in tab order. THIS names even a pinned tab; the bulk
     * scopes leave pinned tabs alone, like VS Code, so "Close all" cannot take away what the user pinned.
     * A caller that closes through something else (a file's buffer) takes the list and closes each itself.
     */
    fun closing(key: DocumentUri, scope: CloseScope): List<DocumentUri> {
        val doomed = TabOrder.closeSet(keys.map { it.toString() }, key.toString(), scope).toSet()
        return tabs.filter { it.key.toString() in doomed && (scope == CloseScope.THIS || it.state != TabState.PINNED) }.map { it.key }
    }

    fun close(key: DocumentUri, scope: CloseScope): EditorGroup = closing(key, scope).fold(this) { g, k -> g.close(k) }

    /** PREVIEW to KEPT ("Keep open"); other states are unchanged. */
    fun keep(key: DocumentUri): EditorGroup =
        if (tab(key)?.state == TabState.PREVIEW) setState(key, TabState.KEPT) else this

    fun pin(key: DocumentUri): EditorGroup {
        val t = tab(key)?.takeIf { it.state != TabState.PINNED } ?: return this
        val rest = tabs.filter { it.key != key }
        return copy(tabs = rest.toMutableList().apply { add(pinnedCount, t.copy(state = TabState.PINNED)) })
    }

    fun unpin(key: DocumentUri): EditorGroup {
        val t = tab(key)?.takeIf { it.state == TabState.PINNED } ?: return this
        val rest = tabs.filter { it.key != key }
        return copy(tabs = rest.toMutableList().apply { add(pinnedCount - 1, t.copy(state = TabState.KEPT)) })
    }

    /** Moves a tab within its own region: pinned tabs among the pinned, the rest among the rest. */
    fun reorder(key: DocumentUri, to: Int): EditorGroup {
        val from = tabs.indexOfFirst { it.key == key }
        if (from < 0) return this
        val pinned = tabs[from].state == TabState.PINNED
        val region = if (pinned) 0 until pinnedCount else pinnedCount until tabs.size
        return copy(tabs = TabOrder.move(tabs, from, to.coerceIn(region.first, region.last)))
    }

    /** Detaches a tab for moving to another group. */
    fun take(key: DocumentUri): Pair<Tab, EditorGroup>? = tab(key)?.let { it to close(key) }

    private fun setState(key: DocumentUri, state: TabState): EditorGroup =
        copy(tabs = tabs.map { if (it.key == key) it.copy(state = state) else it })

    /** Steps back in this group's history; null when there is nowhere to go. */
    fun goBack(): EditorGroup? {
        val current = activeTab?.uri ?: return null
        val (target, rest) = history.stepBack(current) { it in this } ?: return null
        return relocate(target, rest)
    }

    fun goForward(): EditorGroup? {
        val current = activeTab?.uri ?: return null
        val (target, rest) = history.stepForward(current) { it in this } ?: return null
        return relocate(target, rest)
    }

    /** Shows [target] without recording a history step: the caller supplies the new [rest] of the history. */
    private fun relocate(target: DocumentUri, rest: NavHistory): EditorGroup = copy(
        tabs = tabs.map { if (it.key == target.key) it.copy(uri = target) else it },
        active = target.key,
        mru = listOf(target.key) + mru.filter { it != target.key },
        history = rest,
    )

    /** Appends every tab of [other] (a group being merged away) as the least recent, optionally taking its active tab. */
    fun absorb(other: EditorGroup, takeActive: Boolean): EditorGroup {
        val merged = other.tabs.fold(this) { g, t -> g.insert(t, focus = false) }
        val target = other.activeTab
        return if (takeActive && target != null) merged.activate(target.key) else merged
    }

    companion object {
        /**
         * A group from untrusted parts (a saved session): duplicates, extra previews, misplaced
         * pins and a stale [active] or [mru] are repaired instead of rejected, so restore never fails.
         */
        fun sanitized(tabs: List<Tab>, active: DocumentUri?, mru: List<DocumentUri>): EditorGroup {
            val unique = tabs.distinctBy { it.key }
            val firstPreview = unique.indexOfFirst { it.state == TabState.PREVIEW }
            val fixed = unique.mapIndexed { i, t ->
                if (t.state == TabState.PREVIEW && i != firstPreview) t.copy(state = TabState.KEPT) else t
            }
            val ordered = fixed.filter { it.state == TabState.PINNED } + fixed.filter { it.state != TabState.PINNED }
            val keys = ordered.map { it.key }
            val order = (mru.filter { it in keys }.distinct() + keys).distinct()
            return EditorGroup(ordered, active?.takeIf { it in keys } ?: order.firstOrNull(), order)
        }
    }
}
