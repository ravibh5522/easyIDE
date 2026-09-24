package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.ui.theme.Spacing

/**
 * User / Environment / Project layer chips (LLD 17). Environment and project
 * are picked from lists because Settings opens from Home, where no workspace
 * is active; a chip with nothing to pick is disabled.
 */
@Composable
fun SettingsLayerBar(
    tab: LayerTab,
    environments: List<EnvironmentListItem>,
    projects: List<ProjectChoice>,
    onSelect: (LayerTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        FilterChip(
            selected = tab is LayerTab.User,
            onClick = { onSelect(LayerTab.User) },
            label = { Text(stringResource(R.string.settings_layer_user)) },
        )
        PickerChip(
            label = (tab as? LayerTab.Environment)?.let { t -> environments.find { it.environment.id == t.envId }?.environment?.label }
                ?.let { stringResource(R.string.settings_layer_named, stringResource(R.string.settings_layer_environment), it) }
                ?: stringResource(R.string.settings_layer_environment),
            selected = tab is LayerTab.Environment,
            options = environments.map { it.environment.label to LayerTab.Environment(it.environment.id) },
            onSelect = onSelect,
        )
        PickerChip(
            label = (tab as? LayerTab.Project)?.let { t -> projects.find { it.id == t.projectId }?.name }
                ?.let { stringResource(R.string.settings_layer_named, stringResource(R.string.settings_layer_project), it) }
                ?: stringResource(R.string.settings_layer_project),
            selected = tab is LayerTab.Project,
            options = projects.map { it.name to LayerTab.Project(it.id, it.environmentId) },
            onSelect = onSelect,
        )
    }
}

@Composable
private fun PickerChip(label: String, selected: Boolean, options: List<Pair<String, LayerTab>>, onSelect: (LayerTab) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            enabled = options.isNotEmpty(),
            onClick = { expanded = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (name, tab) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(tab) })
            }
        }
    }
}
