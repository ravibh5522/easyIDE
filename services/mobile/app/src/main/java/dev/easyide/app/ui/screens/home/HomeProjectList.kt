package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import dev.easyide.app.R
import dev.easyide.app.ui.theme.Spacing

/** Header of the list pane: search, sort, and the three ways to get a project. */
@Composable
internal fun ListControls(
    query: String,
    sort: ProjectSort,
    onQueryChanged: (String) -> Unit,
    onSortChanged: (ProjectSort) -> Unit,
    onNewProject: () -> Unit,
    onImportFolder: () -> Unit,
    onClone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.home_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChanged("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.home_search_clear))
                        }
                    }
                },
            )
            SortMenu(sort, onSortChanged)
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            FilledTonalButton(onClick = onNewProject) {
                ActionIcon(Icons.Filled.Add)
                Text(stringResource(R.string.home_new_project))
            }
            OutlinedButton(onClick = onImportFolder) {
                ActionIcon(Icons.Filled.CreateNewFolder)
                Text(stringResource(R.string.home_import_folder))
            }
            OutlinedButton(onClick = onClone) {
                ActionIcon(Icons.Filled.CloudDownload)
                Text(stringResource(R.string.home_clone))
            }
        }
    }
}

@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.padding(end = Spacing.s))
}

@Composable
private fun SortMenu(sort: ProjectSort, onSortChanged: (ProjectSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.SwapVert, contentDescription = stringResource(R.string.home_sort))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ProjectSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label())) },
                    leadingIcon = { RadioButton(selected = option == sort, onClick = null) },
                    onClick = {
                        open = false
                        onSortChanged(option)
                    },
                )
            }
        }
    }
}

private fun ProjectSort.label(): Int = when (this) {
    ProjectSort.RECENT -> R.string.home_sort_recent
    ProjectSort.NAME -> R.string.home_sort_name
}

/** The scrolling list itself; [header] scrolls with it so a short window is never all controls. */
@Composable
internal fun ProjectList(
    items: List<ProjectListItem>,
    selectedId: String?,
    nowMs: Long,
    contentPadding: PaddingValues,
    actionsFor: (ProjectListItem) -> ProjectMenuActions,
    onSelect: (ProjectListItem) -> Unit,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item { header() }
        items(items, key = { it.project.id }) { item ->
            ProjectCard(
                item = item,
                nowMs = nowMs,
                selected = item.project.id == selectedId,
                onClick = { onSelect(item) },
                actions = actionsFor(item),
                modifier = Modifier.animateItem(),
            )
        }
        footer?.let { item { it() } }
    }
}

/** Skeleton cards standing where the list will be, so real cards land in place. */
@Composable
internal fun ProjectListSkeleton(contentPadding: PaddingValues, header: @Composable () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
        userScrollEnabled = false,
    ) {
        item { header() }
        items(HomeMetrics.SKELETON_CARD_COUNT) { SkeletonProjectCard() }
    }
}

/** Padding for the list: the scaffold's insets plus the screen gutter. */
internal fun listPadding(insets: PaddingValues, extraBottom: Dp): PaddingValues = PaddingValues(
    start = Spacing.l,
    end = Spacing.l,
    top = insets.calculateTopPadding() + Spacing.m,
    bottom = insets.calculateBottomPadding() + extraBottom,
)
