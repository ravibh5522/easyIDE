package dev.easyide.app.data.settings

import dev.easyide.app.R

/**
 * The `workspace.*` settings: what a project remembers and how many stay alive. Global (user
 * layer) because they describe how the app behaves across projects, not any one project.
 * Consumers: [dev.easyide.app.ui.screens.workspace.session.WorkspaceSession] (restore),
 * `AppNavHost` (launch redirect) and [dev.easyide.app.session.WorkspaceRegistry] (park limit).
 */
object WorkspaceSettingsSchema {

    val restoreOpenTabs = Setting.Bool(
        "workspace.restoreOpenTabs", SettingCategory.WORKSPACE, R.string.setting_restore_open_tabs_title,
        R.string.setting_restore_open_tabs_desc, default = true, scope = SettingScope.G,
    )

    val openLastProjectOnLaunch = Setting.Bool(
        "workspace.openLastProjectOnLaunch", SettingCategory.WORKSPACE, R.string.setting_open_last_project_title,
        R.string.setting_open_last_project_desc, default = false, scope = SettingScope.G,
    )

    /**
     * How many left-behind projects keep their shells and buffers. Two covers the common
     * "glance at another project and come back" without holding a third project's processes.
     */
    val maxParkedProjects = Setting.IntRange(
        "workspace.maxParkedProjects", SettingCategory.WORKSPACE, R.string.setting_max_parked_title,
        R.string.setting_max_parked_desc, default = 2, scope = SettingScope.G, min = 0, max = 8,
    )

    val all: List<Setting<*>> = listOf(restoreOpenTabs, openLastProjectOnLaunch, maxParkedProjects)
}
