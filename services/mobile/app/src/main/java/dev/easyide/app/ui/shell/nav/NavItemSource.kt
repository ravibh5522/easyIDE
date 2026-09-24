package dev.easyide.app.ui.shell.nav

import dev.easyide.app.ui.shell.NavItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One extension's navigation item on its way into the registry; the registry enforces namespacing and caps. */
data class NavContribution(val extensionId: String, val item: NavItem)

/**
 * Where extension navigation items come from. R4 implements it over the extension runtime; until
 * then [NoNavItems] keeps the shell to its core items. [holds] evaluates an item's `when` clause,
 * [badges] maps an item id to what its badge shows.
 */
interface NavItemSource {
    val contributions: StateFlow<List<NavContribution>>
    val badges: StateFlow<Map<String, NavBadgeValue>>
    fun holds(condition: String): Boolean
}

/** No extension contributes anything, so a conditional item never shows (nothing can satisfy its clause). */
object NoNavItems : NavItemSource {
    override val contributions: StateFlow<List<NavContribution>> = MutableStateFlow(emptyList())
    override val badges: StateFlow<Map<String, NavBadgeValue>> = MutableStateFlow(emptyMap())
    override fun holds(condition: String): Boolean = false
}
