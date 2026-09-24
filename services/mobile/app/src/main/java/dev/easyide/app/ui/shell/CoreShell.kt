package dev.easyide.app.ui.shell

/**
 * The built-in navigation items and containers (shell-model.md sections 6.1 and 10). Ids are the
 * ones layout presets, back behaviour and persisted sessions refer to, so they are fixed here once.
 * Built-in `order` values stay below [ShellLimits.EXTENSION_ORDER_FLOOR].
 */
object CoreShell {
    const val HOME = "home"
    const val EXTENSIONS = "extensions"
    const val SETTINGS = "settings"
    const val FILES = "files"
    const val SEARCH = "search"
    const val GIT = "git"
    const val PROBLEMS = "problems"
    const val TERMINAL_NAV = "terminal"
    const val OUTLINE_NAV = "outline"

    const val HOME_PROJECTS = "home.projects"
    const val EXTENSIONS_LIST = "extensions.list"
    const val SETTINGS_CATEGORIES = "settings.categories"
    const val EXPLORER = "explorer"
    const val SEARCH_PANEL = "search"
    const val SOURCE_CONTROL = "scm"
    const val OUTLINE = "outline"
    const val PROBLEMS_PANEL = "problems"
    const val OUTPUT = "output"
    const val TERMINAL = "terminal"

    private fun container(id: String, title: String, icon: String, placement: Placement, scope: ScopeFilter) =
        ContainerSpec(id, title, IconRef(icon), placement, scope)

    private val CONTAINERS = listOf(
        container(HOME_PROJECTS, "Projects", "home", Placement.SIDEBAR, ScopeFilter.APP),
        container(EXTENSIONS_LIST, "Extensions", "extensions", Placement.SIDEBAR, ScopeFilter.APP),
        container(SETTINGS_CATEGORIES, "Settings", "settings", Placement.SIDEBAR, ScopeFilter.APP),
        container(EXPLORER, "Files", "files", Placement.SIDEBAR, ScopeFilter.WORKSPACE),
        container(SEARCH_PANEL, "Search", "search", Placement.SIDEBAR, ScopeFilter.WORKSPACE),
        container(SOURCE_CONTROL, "Source control", "git", Placement.SIDEBAR, ScopeFilter.WORKSPACE),
        container(OUTLINE, "Outline", "outline", Placement.SECONDARY_SIDEBAR, ScopeFilter.WORKSPACE),
        container(TERMINAL, "Terminal", "terminal", Placement.PANEL, ScopeFilter.WORKSPACE),
        container(PROBLEMS_PANEL, "Problems", "problems", Placement.PANEL, ScopeFilter.WORKSPACE),
        container(OUTPUT, "Output", "output", Placement.PANEL, ScopeFilter.WORKSPACE),
    )

    private fun nav(id: String, title: String, icon: String, container: String, order: Int, scope: ScopeFilter) =
        NavItem(id, title, IconRef(icon), NavTarget.Container(container), order, scope)

    private val NAV_ITEMS = listOf(
        nav(HOME, "Home", "home", HOME_PROJECTS, 10, ScopeFilter.APP),
        nav(EXTENSIONS, "Extensions", "extensions", EXTENSIONS_LIST, 20, ScopeFilter.APP),
        nav(SETTINGS, "Settings", "settings", SETTINGS_CATEGORIES, 30, ScopeFilter.APP),
        nav(FILES, "Files", "files", EXPLORER, 10, ScopeFilter.WORKSPACE),
        nav(SEARCH, "Search", "search", SEARCH_PANEL, 20, ScopeFilter.WORKSPACE),
        nav(GIT, "Git", "git", SOURCE_CONTROL, 30, ScopeFilter.WORKSPACE),
        nav(PROBLEMS, "Problems", "problems", PROBLEMS_PANEL, 40, ScopeFilter.WORKSPACE),
        nav(TERMINAL_NAV, "Terminal", "terminal", TERMINAL, 50, ScopeFilter.WORKSPACE),
        nav(OUTLINE_NAV, "Outline", "outline", OUTLINE, 60, ScopeFilter.WORKSPACE),
    )

    fun containers(): ContainerRegistry = CONTAINERS.fold(ContainerRegistry.EMPTY) { r, c -> r.register(c, Origin.Core).registry }

    fun navigation(): NavRegistry = NAV_ITEMS.fold(NavRegistry.EMPTY) { r, n -> r.register(n, Origin.Core).registry }
}
