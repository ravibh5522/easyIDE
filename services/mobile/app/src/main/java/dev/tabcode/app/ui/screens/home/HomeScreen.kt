package dev.tabcode.app.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tabcode.app.R
import dev.tabcode.app.ui.components.EmptyState
import dev.tabcode.app.ui.components.EnvironmentBadge
import dev.tabcode.app.ui.foundation.WidthClass
import dev.tabcode.app.ui.foundation.motionSpec
import dev.tabcode.app.ui.foundation.LocalWindowSize

/**
 * Project List (Home) - the nav root. Adaptive per
 * docs/design-system/arch.md "Responsive design": a single column when narrow,
 * an auto-fitting grid when there is width to use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onOpenProject: (ProjectListItem) -> Unit,
    onNewProject: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowSize = LocalWindowSize.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_home)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            // A labelled FAB is worth the width when there is width; on compact
            // screens the icon alone keeps the list usable.
            if (windowSize.width.atLeastMedium) {
                ExtendedFloatingActionButton(
                    onClick = onNewProject,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.home_new_project)) },
                )
            } else {
                FloatingActionButton(onClick = onNewProject) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_new_project))
                }
            }
        },
    ) { padding ->
        AnimatedVisibility(
            visible = uiState.items.isEmpty() && !uiState.isLoading,
            enter = fadeIn(motionSpec()),
            exit = fadeOut(motionSpec()),
        ) {
            EmptyState(
                icon = Icons.Filled.FolderOpen,
                title = stringResource(R.string.home_empty_title),
                body = stringResource(R.string.home_empty_body),
                modifier = Modifier.fillMaxSize().padding(padding),
                action = {
                    Button(onClick = onNewProject) {
                        Text(stringResource(R.string.home_new_project))
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = uiState.items.isNotEmpty(),
            enter = fadeIn(motionSpec()),
            exit = fadeOut(motionSpec()),
        ) {
            ProjectGrid(
                items = uiState.items,
                columns = windowSize.width.gridColumns(),
                contentPadding = padding,
                onOpenProject = onOpenProject,
            )
        }
    }
}

private fun WidthClass.gridColumns(): GridCells = when (this) {
    WidthClass.COMPACT -> GridCells.Fixed(1)
    // Adaptive rather than a fixed count so a 3-column layout appears only when
    // cards would still be readable, including in split-screen.
    else -> GridCells.Adaptive(minSize = MIN_CARD_WIDTH_DP.dp)
}

@Composable
private fun ProjectGrid(
    items: List<ProjectListItem>,
    columns: GridCells,
    contentPadding: PaddingValues,
    onOpenProject: (ProjectListItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = columns,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = GRID_PADDING_DP.dp,
            end = GRID_PADDING_DP.dp,
            top = contentPadding.calculateTopPadding() + GRID_PADDING_DP.dp,
            bottom = GRID_BOTTOM_PADDING_DP.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
    ) {
        items(items, key = { it.project.id }) { item ->
            ProjectCard(
                item = item,
                onClick = { onOpenProject(item) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun ProjectCard(
    item: ProjectListItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(CARD_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = item.project.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val environment = item.environment
                if (environment != null) {
                    EnvironmentBadge(
                        label = environment.label,
                        state = environment.state,
                        sharedWithCount = item.sharedWithCount,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.home_environment_missing),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private const val MIN_CARD_WIDTH_DP = 260
private const val GRID_PADDING_DP = 16
private const val GRID_SPACING_DP = 12
private const val GRID_BOTTOM_PADDING_DP = 96
private const val CARD_PADDING_DP = 16
