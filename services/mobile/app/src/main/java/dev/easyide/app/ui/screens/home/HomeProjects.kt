package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.components.SkeletonBar
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.Sweep
import kotlinx.coroutines.delay

/** Every way a search or sort of the list can be reported and every way out of an empty list. */
internal class ProjectsActions(
    val onQueryChanged: (String) -> Unit,
    val onSortChanged: (ProjectSort) -> Unit,
    val onOpenPage: (String) -> Unit,
    val onNewProject: () -> Unit,
    val actionsFor: (ProjectListItem) -> ProjectMenuActions,
)

/**
 * All projects, in the side panel form (rows directly on the panel): a header with the count,
 * search and sort on one line, then one row per project. With none at all it is the first-run state
 * (one command); with a search that matches none it says so and offers to clear it. Loading shows two
 * static bars.
 */
@Composable
internal fun ProjectsSection(state: HomeUiState, nowMs: Long, selectedId: String?, actions: ProjectsActions) {
    KitSection(stringResource(R.string.home_section_projects), count = state.projectCount.takeIf { !state.isLoading }, flat = true, collapsible = true) {
        when {
            state.isLoading -> ProjectsSkeleton()
            state.projectCount == 0 -> KitEmptyState(
                art = EmptyArt.Projects,
                message = stringResource(R.string.home_projects_empty),
                action = KitAction(stringResource(R.string.home_new_project), actions.onNewProject),
            )
            else -> {
                SearchSortLine(state.query, state.sort, actions.onQueryChanged, actions.onSortChanged)
                if (state.visible.isEmpty()) {
                    KitEmptyState(
                        art = EmptyArt.Search,
                        message = stringResource(R.string.home_no_matches, state.query.trim()),
                        action = KitAction(stringResource(R.string.home_no_matches_clear)) { actions.onQueryChanged("") },
                    )
                }
                state.visible.forEach { item ->
                    ProjectRow(
                        item = item,
                        nowMs = nowMs,
                        selected = item.project.id == selectedId,
                        onClick = { actions.onOpenPage(item.project.id) },
                        actions = actions.actionsFor(item),
                    )
                }
            }
        }
    }
}

/**
 * The stage's summary of the projects: the most recently opened few, on the same rows as the panel,
 * for a window where the panel is not beside the stage. Nothing while loading or when there are none.
 */
@Composable
internal fun ProjectsSummary(state: HomeUiState, nowMs: Long, actions: ProjectsActions) {
    if (state.isLoading || state.projectCount == 0) return
    val recent = remember(state.all) { state.all.searchedAndSorted("", ProjectSort.RECENT).take(HomeMetrics.SUMMARY_PROJECTS) }
    KitSection(stringResource(R.string.home_section_projects), count = state.projectCount, collapsible = true) {
        recent.forEach { item ->
            ProjectRow(item, nowMs, selected = false, onClick = { actions.onOpenPage(item.project.id) }, actions = actions.actionsFor(item))
        }
    }
}

/** Two static bars where the rows will land, and nothing at all for the first 300ms (identity.md 10). */
@Composable
private fun ProjectsSkeleton() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(Sweep.DELAY_MS)
        shown = true
    }
    if (!shown) return
    val bar = Kit.colors.panelBorder
    Column(Modifier.padding(Kit.control.hPad), verticalArrangement = Arrangement.spacedBy(Kit.space.s)) {
        SkeletonBar(bar, Kit.space.l, Modifier.fillMaxWidth(HomeMetrics.SKELETON_TITLE_FRACTION))
        SkeletonBar(bar, Kit.space.m, Modifier.fillMaxWidth(HomeMetrics.SKELETON_SUBTITLE_FRACTION))
    }
}
