package dev.easyide.app.ui.screens.home

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
import dev.easyide.app.R
import dev.easyide.app.ui.components.EmptyState
import dev.easyide.app.ui.components.SkeletonBar
import dev.easyide.app.ui.components.EnvironmentBadge
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.foundation.motionSpec
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.theme.Spacing

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

        // Skeleton cards in the same grid, so the first real cards land where
        // the placeholders were instead of popping into an empty screen.
        AnimatedVisibility(
            visible = uiState.items.isEmpty() && uiState.isLoading,
            enter = fadeIn(motionSpec()),
            exit = fadeOut(motionSpec()),
        ) {
            SkeletonGrid(columns = windowSize.width.gridColumns(), contentPadding = padding)
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
    else -> GridCells.Adaptive(minSize = MIN_CARD_WIDTH)
}

/** Grid spacing shared by the real and skeleton grids so they line up exactly. */
private fun gridPadding(contentPadding: PaddingValues) = PaddingValues(
    start = Spacing.l,
    end = Spacing.l,
    top = contentPadding.calculateTopPadding() + Spacing.l,
    // Clears the FAB so the last card is never hidden under it.
    bottom = GRID_BOTTOM_PADDING,
)

@Composable
private fun SkeletonGrid(columns: GridCells, contentPadding: PaddingValues) {
    LazyVerticalGrid(
        columns = columns,
        modifier = Modifier.fillMaxSize(),
        contentPadding = gridPadding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
        userScrollEnabled = false,
    ) {
        items(SKELETON_CARD_COUNT) { SkeletonCard() }
    }
}

/** Shaped like [ProjectCard]: a title line over an environment badge. */
@Composable
private fun SkeletonCard() {
    val placeholder = MaterialTheme.colorScheme.surfaceContainerHigh
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            SkeletonBar(placeholder, Spacing.l, Modifier.fillMaxWidth(SKELETON_TITLE_FRACTION))
            SkeletonBar(placeholder, Spacing.l + Spacing.xs, Modifier.fillMaxWidth(SKELETON_BADGE_FRACTION))
        }
    }
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
        contentPadding = gridPadding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
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
            modifier = Modifier.padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text(
                text = item.project.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
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

private val MIN_CARD_WIDTH = 260.dp
private val GRID_BOTTOM_PADDING = 96.dp
private const val SKELETON_CARD_COUNT = 6
private const val SKELETON_TITLE_FRACTION = 0.6f
private const val SKELETON_BADGE_FRACTION = 0.35f
