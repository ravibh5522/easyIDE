package dev.easyide.app.ui.shell

import dev.easyide.app.ui.commands.CommandIds

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

    /** Workspace destinations that run something instead of showing a container (the old rail's actions). */
    const val COMMANDS = "commands"
    const val PROJECTS = "projects"
    const val CLOSE_PROJECT = "close-project"

    /** The command ids the two workspace-only actions run; the workspace registers them next to its other commands. */
    const val BACK_TO_PROJECTS_COMMAND = "easyide.workspace.backToProjects"
    const val CLOSE_PROJECT_COMMAND = "easyide.workspace.closeProject"

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

    /** Registration order is fallback order within a placement: Home first for app scope, Files first for a workspace. */
    private val CONTAINERS = listOf(
        container(HOME_PROJECTS, "Projects", "home", Placement.SIDEBAR, ScopeFilter.APP),
        container(EXPLORER, "Files", "files", Placement.SIDEBAR, ScopeFilter.WORKSPACE),
        container(SEARCH_PANEL, "Search", "search", Placement.SIDEBAR, ScopeFilter.WORKSPACE),
        container(SOURCE_CONTROL, "Source control", "git", Placement.SIDEBAR, ScopeFilter.WORKSPACE),
        container(EXTENSIONS_LIST, "Extensions", "extensions", Placement.SIDEBAR, ScopeFilter.BOTH),
        container(SETTINGS_CATEGORIES, "Settings", "settings", Placement.SIDEBAR, ScopeFilter.BOTH),
        container(OUTLINE, "Outline", "outline", Placement.SECONDARY_SIDEBAR, ScopeFilter.WORKSPACE),
        container(TERMINAL, "Terminal", "terminal", Placement.PANEL, ScopeFilter.WORKSPACE),
        container(PROBLEMS_PANEL, "Problems", "problems", Placement.PANEL, ScopeFilter.WORKSPACE),
        container(OUTPUT, "Output", "output", Placement.PANEL, ScopeFilter.WORKSPACE),
    )

    private fun nav(id: String, title: String, icon: String, container: String, order: Int, scope: ScopeFilter) =
        NavItem(id, title, IconRef(icon), NavTarget.Container(container), order, scope)

    private fun action(id: String, title: String, icon: String, command: String, order: Int) =
        NavItem(id, title, IconRef(icon), NavTarget.Command(command), order, ScopeFilter.WORKSPACE)

    /**
     * Extensions and Settings are in both scopes (a workspace lists them in its primary panel and opens
     * their pages in the stage), so their order sits after the workspace's own destinations: Files,
     * Search, Git and Terminal are the four a phone's bottom bar keeps beside "More".
     */
    private val NAV_ITEMS = listOf(
        nav(HOME, "Home", "home", HOME_PROJECTS, 10, ScopeFilter.APP),
        nav(FILES, "Files", "files", EXPLORER, 10, ScopeFilter.WORKSPACE),
        nav(SEARCH, "Search", "search", SEARCH_PANEL, 20, ScopeFilter.WORKSPACE),
        nav(GIT, "Git", "git", SOURCE_CONTROL, 30, ScopeFilter.WORKSPACE),
        nav(TERMINAL_NAV, "Terminal", "terminal", TERMINAL, 40, ScopeFilter.WORKSPACE),
        nav(PROBLEMS, "Problems", "problems", PROBLEMS_PANEL, 50, ScopeFilter.WORKSPACE),
        nav(OUTLINE_NAV, "Outline", "outline", OUTLINE, 60, ScopeFilter.WORKSPACE),
        nav(EXTENSIONS, "Extensions", "extensions", EXTENSIONS_LIST, 70, ScopeFilter.BOTH),
        nav(SETTINGS, "Settings", "settings", SETTINGS_CATEGORIES, 80, ScopeFilter.BOTH),
        action(COMMANDS, "Commands", "commands", CommandIds.SHOW_COMMANDS, 90),
        action(PROJECTS, "Projects", "back", BACK_TO_PROJECTS_COMMAND, 95),
        action(CLOSE_PROJECT, "Close", "close", CLOSE_PROJECT_COMMAND, 96),
    )

    /** The navigation item that leads to [container], so showing a container by command marks the same destination as tapping it. */
    fun navIdOf(container: String): String? = NAV_ITEMS.firstOrNull { (it.target as? NavTarget.Container)?.id == container }?.id

    fun containers(): ContainerRegistry = CONTAINERS.fold(ContainerRegistry.EMPTY) { r, c -> r.register(c, Origin.Core).registry }

    fun navigation(): NavRegistry = NAV_ITEMS.fold(NavRegistry.EMPTY) { r, n -> r.register(n, Origin.Core).registry }
}
