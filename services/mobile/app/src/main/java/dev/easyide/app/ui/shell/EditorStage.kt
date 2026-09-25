package dev.easyide.app.ui.shell

import dev.easyide.app.ui.screens.workspace.layout.CloseScope

/** Where `openDocument` puts a document: the active group, the group beside it (created on demand), or a brand new one. */
enum class GroupTarget { ACTIVE, BESIDE, NEW }

enum class SplitAxis { ROW, COLUMN }

/** The one entry point's options (shell-model.md section 4.1). */
data class OpenOptions(
    val group: GroupTarget = GroupTarget.ACTIVE,
    val preview: Boolean = false,
    val focus: Boolean = true,
)

/**
 * The main stage: one to [ShellLimits.MAX_GROUPS] editor groups along one [axis], one of them active.
 *
 * Rules (shell-model.md sections 4.1, 4.2, 8): a document opens at most once on the stage, so opening
 * one that is already open focuses it (an explicit "beside" or "new group" moves it there instead);
 * a type with `multiple` may appear once per group. A group emptied by closing or moving a tab is
 * removed unless it is the only one, so documents are never stranded in a hidden split.
 */
data class EditorStage(
    val groups: List<EditorGroup> = listOf(EditorGroup()),
    val active: Int = 0,
    val axis: SplitAxis = SplitAxis.ROW,
) {
    init {
        require(groups.size in 1..ShellLimits.MAX_GROUPS) { "1 to ${ShellLimits.MAX_GROUPS} groups" }
        require(active in groups.indices) { "active group out of range" }
    }

    val activeGroup: EditorGroup get() = groups[active]

    /** Every open document (with its sub-page), group by group. */
    val documents: List<DocumentUri> get() = groups.flatMap { g -> g.tabs.map { it.uri } }

    private fun update(i: Int, f: (EditorGroup) -> EditorGroup): EditorStage =
        copy(groups = groups.mapIndexed { n, g -> if (n == i) f(g) else g })

    /**
     * Opens [uri]. [capacity] is how many groups the window can show
     * ([ShellLimits.groupCapacity]); when there is no room "beside" falls back to the active group,
     * which on a phone is the one group, so the new document simply becomes the visible one.
     */
    fun open(uri: DocumentUri, options: OpenOptions, capacity: Int, type: DocumentType): EditorStage {
        val key = uri.key
        val holder = groups.indexOfFirst { key in it }
        if (!type.multiple && holder >= 0 && options.group == GroupTarget.ACTIVE) {
            return update(holder) { it.open(uri, options.preview, options.focus) }.focusing(holder, options.focus)
        }
        val (base, target) = destination(options.group, type.supportsSplit, capacity)
        val from = base.groups.indexOfFirst { key in it }
        val placed = if (!type.multiple && from >= 0 && from != target) {
            base.relocate(from, key, target, uri, options)
        } else {
            base.update(target) { it.open(uri, options.preview, options.focus) }
        }
        val focusIndex = if (type.multiple) target else placed.groups.indexOfFirst { key in it }
        return placed.focusing(focusIndex, options.focus)
    }

    private fun focusing(index: Int, focus: Boolean): EditorStage = if (focus && index >= 0) copy(active = index) else this

    private fun relocate(from: Int, key: DocumentUri, to: Int, uri: DocumentUri, options: OpenOptions): EditorStage {
        val (tab, rest) = groups[from].take(key) ?: return this
        val kept = if (!options.preview && tab.state == TabState.PREVIEW) TabState.KEPT else tab.state
        return update(from) { rest }.update(to) { it.insert(Tab(uri, kept), options.focus) }.dropIfEmpty(from)
    }

    private fun destination(target: GroupTarget, splittable: Boolean, capacity: Int): Pair<EditorStage, Int> {
        val canGrow = splittable && groups.size < capacity.coerceIn(1, ShellLimits.MAX_GROUPS)
        return when {
            target == GroupTarget.ACTIVE || !splittable -> this to active
            target == GroupTarget.NEW && canGrow -> withGroupAfterActive() to active + 1
            active + 1 < groups.size -> this to active + 1
            canGrow -> withGroupAfterActive() to active + 1
            else -> this to active
        }
    }

    private fun withGroupAfterActive(): EditorStage =
        copy(groups = groups.toMutableList().apply { add(active + 1, EditorGroup()) })

    private fun dropIfEmpty(i: Int): EditorStage {
        if (groups.size == 1 || !groups[i].isEmpty) return this
        val next = when {
            active > i -> active - 1
            active == i -> maxOf(0, i - 1)
            else -> active
        }
        return copy(groups = groups.filterIndexed { n, _ -> n != i }, active = next)
    }

    fun close(group: Int, key: DocumentUri): EditorStage = update(group) { it.close(key) }.dropIfEmpty(group)

    fun close(group: Int, key: DocumentUri, scope: CloseScope): EditorStage =
        update(group) { it.close(key, scope) }.dropIfEmpty(group)

    /** Closes every document [matches] selects, pinned or not (used by "close all of this type"). */
    fun closeWhere(matches: (DocumentUri) -> Boolean): EditorStage =
        groups.indices.reversed().fold(this) { s, i ->
            s.update(i) { g -> g.tabs.filter { matches(it.key) }.fold(g) { acc, t -> acc.close(t.key) } }.dropIfEmpty(i)
        }

    fun pin(group: Int, key: DocumentUri) = update(group) { it.pin(key) }

    fun unpin(group: Int, key: DocumentUri) = update(group) { it.unpin(key) }

    fun keep(group: Int, key: DocumentUri) = update(group) { it.keep(key) }

    fun reorder(group: Int, key: DocumentUri, to: Int) = update(group) { it.reorder(key, to) }

    fun activate(group: Int, key: DocumentUri): EditorStage =
        if (group in groups.indices && key in groups[group]) update(group) { it.activate(key) }.copy(active = group) else this

    fun focusGroup(i: Int): EditorStage = if (i in groups.indices) copy(active = i) else this

    /** Moves a tab to another group and focuses it there. */
    fun move(from: Int, key: DocumentUri, to: Int): EditorStage {
        if (from == to || from !in groups.indices || to !in groups.indices) return this
        val (tab, rest) = groups[from].take(key) ?: return this
        val placed = update(from) { rest }.update(to) { it.insert(tab, focus = true) }.dropIfEmpty(from)
        return placed.copy(active = placed.groups.indexOfFirst { key in it })
    }

    /** Adds an empty group after the active one and focuses it; nothing happens at [capacity]. */
    fun split(capacity: Int): EditorStage =
        if (groups.size >= capacity.coerceIn(1, ShellLimits.MAX_GROUPS)) this
        else withGroupAfterActive().copy(active = active + 1)

    /** Merges group [i] into its neighbour (the previous one, or the next for the first); a single group stays. */
    fun unsplit(i: Int): EditorStage = if (groups.size < 2 || i !in groups.indices) this else merge(i, if (i > 0) i - 1 else 1)

    private fun merge(from: Int, into: Int): EditorStage {
        val merged = groups[into].absorb(groups[from], takeActive = active == from)
        val gs = groups.toMutableList().also { it[into] = merged; it.removeAt(from) }
        fun shifted(i: Int) = if (i > from) i - 1 else i
        return copy(groups = gs, active = shifted(if (active == from) into else active))
    }

    /** Merges surplus groups into their left neighbour, so a smaller window never loses a document. */
    fun fit(capacity: Int): EditorStage {
        var s = this
        while (s.groups.size > capacity.coerceIn(1, ShellLimits.MAX_GROUPS)) s = s.merge(s.groups.lastIndex, s.groups.lastIndex - 1)
        return s
    }

    /** Shapes the stage to [count] groups along [newAxis] (a layout preset): merges extras, adds empty groups. */
    fun arranged(count: Int, newAxis: SplitAxis, capacity: Int): EditorStage {
        val want = count.coerceIn(1, capacity.coerceIn(1, ShellLimits.MAX_GROUPS))
        val fitted = copy(axis = newAxis).fit(want)
        return fitted.copy(groups = fitted.groups + List(want - fitted.groups.size) { EditorGroup() })
    }

    fun back(): EditorStage? = activeGroup.goBack()?.let { g -> update(active) { g } }

    fun forward(): EditorStage? = activeGroup.goForward()?.let { g -> update(active) { g } }

    /** Forgets the active group's history: a stage the user was not looking at starts a fresh trail. */
    fun clearHistory(): EditorStage = update(active) { it.copy(history = NavHistory.EMPTY) }
}
