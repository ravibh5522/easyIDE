package dev.easyide.app.ui.shell

/** What a navigation item does: reveal a container, or run a command. */
sealed interface NavTarget {
    data class Container(val id: String) : NavTarget
    data class Command(val id: String) : NavTarget
}

/** Where a badge's number or dot comes from: a path into a view's data. */
data class NavBadge(val view: String, val path: String)

/** A `navigation` contribution (shell-model.md section 6.1). [condition] is the raw `when` clause. */
data class NavItem(
    val id: String,
    val title: String,
    val icon: IconRef,
    val target: NavTarget,
    val order: Int,
    val scope: ScopeFilter,
    val condition: String? = null,
    val badge: NavBadge? = null,
)

/** The user's `shell.navigation.order`, `.hidden` and `.pinned`. */
data class NavPrefs(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val pinned: Set<String> = emptySet(),
)

/** What the host knows and the engine does not: whether a `when` clause holds and whether a target exists. */
class NavEnv(val holds: (String) -> Boolean, val resolves: (NavTarget) -> Boolean)

/**
 * Navigation items contributed by core and extensions. Immutable; a change returns a new registry.
 *
 * Extensions cannot displace core: their `order` is raised to [ShellLimits.EXTENSION_ORDER_FLOOR]
 * (values below it are reserved for built-ins), ties go to core, and a core id is never taken over.
 * An extension may add [ShellLimits.NAV_ITEMS_PER_EXTENSION] items with titles of at most
 * [ShellLimits.NAV_TITLE_MAX] characters, ids `<extension id>.<name>`.
 */
class NavRegistry private constructor(private val entries: List<Entry>) {
    private class Entry(val item: NavItem, val origin: Origin)

    fun register(item: NavItem, origin: Origin): Registered<NavRegistry> {
        val refusal = refusal(item, origin)
        if (refusal != null) return Registered(this, listOf(Rejection(item.id, refusal)))
        val placed = if (origin is Origin.Extension) item.copy(order = maxOf(item.order, ShellLimits.EXTENSION_ORDER_FLOOR)) else item
        return Registered(NavRegistry(entries + Entry(placed, origin)))
    }

    private fun refusal(item: NavItem, origin: Origin): RejectReason? = when {
        entries.any { it.item.id == item.id } -> RejectReason.DUPLICATE_ID
        !origin.owns(item.id) -> RejectReason.NOT_NAMESPACED
        origin is Origin.Extension && item.title.length > ShellLimits.NAV_TITLE_MAX -> RejectReason.TITLE_TOO_LONG
        origin is Origin.Extension && entries.count { it.origin == origin } >= ShellLimits.NAV_ITEMS_PER_EXTENSION ->
            RejectReason.TOO_MANY
        else -> null
    }

    fun unregister(extensionId: String): NavRegistry {
        val gone = Origin.Extension(extensionId)
        return NavRegistry(entries.filter { it.origin != gone })
    }

    fun byId(id: String): NavItem? = entries.firstOrNull { it.item.id == id }?.item

    /** The extension that contributed [id], or null for a core item or an unknown id. */
    fun packOf(id: String): String? = entries.firstOrNull { it.item.id == id }?.origin?.extensionId

    /**
     * The items to show in [scope], in display order. An item is dropped when its scope, `when`
     * clause or target says so, or the user hid it, except that hiding can never empty the surface.
     * The user's order comes first; anything they have not ordered follows by default order (core
     * before extension on a tie, then registration order).
     */
    fun visible(scope: ShellScope, prefs: NavPrefs, env: NavEnv): List<NavItem> {
        val available = entries.filter {
            val item = it.item
            item.scope.includes(scope) && (item.condition == null || env.holds(item.condition)) && env.resolves(item.target)
        }
        val shown = available.filter { it.item.id !in prefs.hidden }.ifEmpty { available }
        val byDefault = shown.sortedWith(compareBy({ it.item.order }, { it.origin != Origin.Core }))
        val ranked = prefs.order.distinct().mapNotNull { id -> byDefault.firstOrNull { it.item.id == id } }
        return (ranked + byDefault.filter { it !in ranked }).map { it.item }
    }

    companion object {
        val EMPTY = NavRegistry(emptyList())
    }
}

/** A bottom bar or rail split into what fits and the "More" overflow, both in display order. */
data class NavSplit(val visible: List<NavItem>, val more: List<NavItem>)

object NavLayout {
    /**
     * Fits [items] into [capacity] cells. Pinned items keep their cell (the first [capacity] of them
     * if there are more); the remaining cells go to the other items in order; the rest overflow to
     * "More" in the same order. Compact windows use [ShellLimits.COMPACT_NAV_CAPACITY].
     */
    fun split(items: List<NavItem>, capacity: Int, pinned: Set<String>): NavSplit {
        if (items.size <= capacity) return NavSplit(items, emptyList())
        val keep = items.filter { it.id in pinned }.take(capacity)
        val fill = items.filter { it !in keep }.take(capacity - keep.size)
        val shown = (keep + fill).toSet()
        return NavSplit(items.filter { it in shown }, items.filter { it !in shown })
    }
}
