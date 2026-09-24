package dev.easyide.app.ui.screens.settings

/**
 * The User / Environment / Project control as pure choices: which segment is selected and which
 * tab a tap on a segment selects. Environment and Project each need a target, so a segment with
 * nothing to pick is unavailable rather than a dead end.
 */
object SettingsLayerModel {
    const val USER = 0
    const val ENVIRONMENT = 1
    const val PROJECT = 2

    fun segmentOf(tab: LayerTab): Int = when (tab) {
        is LayerTab.User -> USER
        is LayerTab.Environment -> ENVIRONMENT
        is LayerTab.Project -> PROJECT
    }

    fun isAvailable(segment: Int, environments: List<EnvironmentListItem>, projects: List<ProjectChoice>): Boolean = when (segment) {
        ENVIRONMENT -> environments.isNotEmpty()
        PROJECT -> projects.isNotEmpty()
        else -> true
    }

    /**
     * The tab [segment] selects: the current target when [current] is already of that kind, otherwise
     * the first environment or project. Null when the segment has nothing to pick.
     */
    fun select(segment: Int, current: LayerTab, environments: List<EnvironmentListItem>, projects: List<ProjectChoice>): LayerTab? =
        when (segment) {
            USER -> LayerTab.User
            ENVIRONMENT -> current.takeIf { it is LayerTab.Environment }
                ?: environments.firstOrNull()?.let { LayerTab.Environment(it.environment.id) }
            PROJECT -> current.takeIf { it is LayerTab.Project }
                ?: projects.firstOrNull()?.let { LayerTab.Project(it.id, it.environmentId) }
            else -> null
        }
}
