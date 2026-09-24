package dev.easyide.app.extensions.adapters

import dev.easyide.extensions.contrib.ContributionOverrides
import dev.easyide.extensions.contrib.ContributionSnapshot
import dev.easyide.extensions.contrib.KeyRowContribution
import dev.easyide.extensions.contrib.MenuItemContribution
import dev.easyide.extensions.contrib.Owned
import dev.easyide.extensions.contrib.StatusBarAlignment
import dev.easyide.extensions.contrib.StatusBarItemContribution

/**
 * The `workbench.contributions.order` locations the app renders (customization.md sec 6):
 * menu ids, `statusBar.left`/`statusBar.right` and `keyRows`. Stages, views and the
 * activity bar are not rendered from contributions yet, so their entries have no location
 * and cannot be moved (hiding still applies to them).
 */
object ContributionLocations {

    /** Where [o] can be reordered, or null; its id inside that location is `o.ref.id`. */
    fun of(o: Owned<*>): String? = when (val v = o.value) {
        is MenuItemContribution -> v.menuId
        is StatusBarItemContribution -> ContributionOverrides.statusBarLocation(v.alignment)
        is KeyRowContribution -> ContributionOverrides.KEY_ROWS
        else -> null
    }

    /** The visible order of [location] now, ignoring `when`: what a move up/down rewrites. */
    fun effectiveIds(
        location: String,
        snapshot: ContributionSnapshot,
        userRows: List<KeyRowContribution>,
        hidden: Set<String>,
        order: Map<String, List<String>>,
    ): List<String> = when (location) {
        ContributionOverrides.KEY_ROWS -> KeyRows.available(snapshot, userRows, hidden, order).filterNot { it.hidden }.map { it.id }
        ContributionOverrides.STATUS_BAR_LEFT -> StatusItems.orderedIds(snapshot, StatusBarAlignment.LEFT, hidden, order)
        ContributionOverrides.STATUS_BAR_RIGHT -> StatusItems.orderedIds(snapshot, StatusBarAlignment.RIGHT, hidden, order)
        else -> MenuModel.orderedIds(location, snapshot, hidden, order)
    }

    /**
     * The whole order map after moving [id] by [delta] in [location], or null when it cannot
     * move that way. Other locations are kept as they are.
     */
    fun moved(order: Map<String, List<String>>, location: String, effective: List<String>, id: String, delta: Int): Map<String, List<String>>? {
        val next = ContributionOverrides.moved(effective, id, delta) ?: return null
        return order + (location to next)
    }
}
