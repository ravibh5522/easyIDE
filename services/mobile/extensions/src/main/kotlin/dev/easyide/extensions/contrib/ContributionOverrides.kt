package dev.easyide.extensions.contrib

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The user's `workbench.contributions.hidden` and `workbench.contributions.order`
 * (customization.md sec 6), already resolved across layers: [hidden] holds ref texts
 * (`<kind>:<location>:<id>`) minus [NON_HIDEABLE]; [order] maps a location to the ids that
 * move to its front, in that order.
 */
data class ContributionOverrides(val hidden: Set<String>, val order: Map<String, List<String>>) {

    fun isHidden(ref: ContributionRef): Boolean = ref.toString() in hidden

    /**
     * Per location: drop hidden refs; sort by [defaultOrder] (menus: group rules, status
     * bar: priority; null keeps the given order); then the ids listed in `order[location]`
     * move to the front in listed order, unlisted ones keep their default order after them.
     * Unknown ids are ignored (their extension may just be disabled).
     */
    fun <T> apply(location: String, items: List<T>, ref: (T) -> ContributionRef, defaultOrder: Comparator<in T>? = null): List<T> {
        val visible = items.filter { !isHidden(ref(it)) }
        val sorted = if (defaultOrder == null) visible else visible.sortedWith(defaultOrder)
        return reorder(location, sorted) { ref(it).id }
    }

    /** Only the `order` step of [apply], for lists already filtered and sorted. */
    fun <T> reorder(location: String, items: List<T>, id: (T) -> String): List<T> {
        val listed = order[location]?.takeIf { it.isNotEmpty() } ?: return items
        val rank = HashMap<String, Int>()
        listed.forEachIndexed { i, s -> rank.putIfAbsent(s, i) }
        // Stable sort: listed ids by their rank, everything else after, in default order.
        return items.withIndex()
            .sortedWith(compareBy({ rank[id(it.value)] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }

    companion object {
        val NONE = ContributionOverrides(emptySet(), emptyMap())

        /** Status bar locations; the others are menu ids, [KEY_ROWS], [STAGES], [ACTIVITY_BAR], [PANEL]. */
        const val STATUS_BAR_LEFT = "statusBar.left"
        const val STATUS_BAR_RIGHT = "statusBar.right"
        const val KEY_ROWS = "keyRows"
        const val STAGES = "stages"
        const val ACTIVITY_BAR = "activitybar"
        const val PANEL = "panel"

        /**
         * Refs no override can hide (customization.md sec 18 `SettingsPolicy.NON_HIDEABLE`):
         * the palette, the Settings entry and the safe-mode banner, so a settings file can
         * never lock the user out. Listed in `hidden` they are ignored and warned about.
         */
        val NON_HIDEABLE: Set<String> = setOf(
            "command:workbench.action.showCommands",
            "view:builtin.settings",
            "statusBar:builtin.safeMode",
        )

        fun isHideable(ref: String): Boolean = ref !in NON_HIDEABLE

        fun of(hidden: Collection<String>, order: Map<String, List<String>>): ContributionOverrides =
            ContributionOverrides(hidden.filterTo(HashSet(), ::isHideable), order)

        fun statusBarLocation(alignment: StatusBarAlignment): String =
            if (alignment == StatusBarAlignment.RIGHT) STATUS_BAR_RIGHT else STATUS_BAR_LEFT

        /**
         * `{location: [id...]}`; entries that are not string arrays are skipped, non-string
         * ids dropped. Anything but an object means no ordering.
         */
        fun parseOrder(value: JsonElement?): Map<String, List<String>> {
            val root = value as? JsonObject ?: return emptyMap()
            return buildMap {
                for ((location, ids) in root) {
                    val array = ids as? JsonArray ?: continue
                    put(location, array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content })
                }
            }
        }

        /** True when [value] is a well-formed `workbench.contributions.order` object. */
        fun isOrderValue(value: JsonElement): Boolean =
            value is JsonObject && value.values.all { ids -> ids is JsonArray && ids.all { it is JsonPrimitive && it.isString } }

        fun encodeOrder(order: Map<String, List<String>>): JsonObject =
            JsonObject(order.mapValues { (_, ids) -> JsonArray(ids.map(::JsonPrimitive)) })

        /**
         * The full `hidden` list to write after hiding or showing [ref] (customization.md 3.3:
         * the UI writes the whole effective list). Null when [ref] may not be hidden.
         */
        fun withHidden(current: List<String>, ref: String, hide: Boolean): List<String>? {
            if (hide && !isHideable(ref)) return null
            val rest = current.filter { it != ref }
            return if (hide) rest + ref else rest
        }

        /**
         * The `order[location]` list after moving [id] by [delta] places within [effective]
         * (the location's current visible order). The whole resulting order is written, so
         * the move holds however default ordering changes later. Null when [id] is not
         * there or cannot move that way.
         */
        fun moved(effective: List<String>, id: String, delta: Int): List<String>? {
            val from = effective.indexOf(id)
            if (from < 0) return null
            val to = from + delta
            if (delta == 0 || to !in effective.indices) return null
            val out = effective.toMutableList()
            out.removeAt(from)
            out.add(to, id)
            return out
        }
    }
}
