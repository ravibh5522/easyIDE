package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitChoice
import dev.easyide.app.ui.kit.KitTabs
import dev.easyide.app.ui.kit.TabStyle

/**
 * User | Environment | Project, the layer every edit writes to (LLD 17). Environment and Project
 * each need a target, picked inline under the control; Settings opens from Home, where no workspace
 * is active. A segment with nothing to pick says so instead of doing nothing.
 */
@Composable
fun SettingsLayerControl(
    tab: LayerTab,
    environments: List<EnvironmentListItem>,
    projects: List<ProjectChoice>,
    onSelect: (LayerTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    var empty by remember { mutableStateOf<Int?>(null) }
    val labels = listOf(R.string.settings_layer_user, R.string.settings_layer_environment, R.string.settings_layer_project).map { stringResource(it) }
    Column(modifier) {
        KitTabs(
            labels = labels,
            selected = SettingsLayerModel.segmentOf(tab),
            onSelect = { segment ->
                val next = SettingsLayerModel.select(segment, tab, environments, projects)
                empty = if (next == null) segment else null
                next?.let(onSelect)
            },
            style = TabStyle.Segmented,
        )
        when (tab) {
            is LayerTab.Environment -> KitChoice(
                environments.map { it.environment.id }, tab.envId,
                { id -> environments.firstOrNull { it.environment.id == id }?.environment?.label ?: id },
                { onSelect(LayerTab.Environment(it)) },
            )
            is LayerTab.Project -> projects.firstOrNull { it.id == tab.projectId }?.let { current ->
                KitChoice(projects, current, { it.name }, { onSelect(LayerTab.Project(it.id, it.environmentId)) })
            }
            LayerTab.User -> Unit
        }
        empty?.let {
            BasicText(
                stringResource(if (it == SettingsLayerModel.ENVIRONMENT) R.string.settings_layer_no_environment else R.string.settings_layer_no_project),
                Modifier.padding(top = Kit.space.s),
                style = Kit.text.caption.copy(color = Kit.colors.textMuted),
            )
        }
    }
}
