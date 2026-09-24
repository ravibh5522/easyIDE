package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.easyide.app.R
import kotlinx.coroutines.delay
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitChoice
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.Sweep
import dev.easyide.app.ui.components.SkeletonBar

/** Every way a search or sort of the list can be reported and every way out of an empty list. */
internal class ProjectsActions(
    val onQueryChanged: (String) -> Unit,
    val onSortChanged: (ProjectSort) -> Unit,
    val onOpenPage: (String) -> Unit,
    val onNewProject: () -> Unit,
    val actionsFor: (ProjectListItem) -> ProjectMenuActions,
)

private val SEARCH_KEYBOARD = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search)

/**
 * All projects: search, sort, and rows. With none at all it is the first-run state (one command);
 * with a search that matches none it says so and offers to clear it. Loading shows two static bars.
 */
@Composable
internal fun ProjectsSection(state: HomeUiState, nowMs: Long, selectedId: String?, actions: ProjectsActions) {
    val space = Kit.space
    val sortLabels = ProjectSort.entries.associateWith { it.label() }
    KitSection(stringResource(R.string.home_section_projects)) {
        when {
            state.isLoading -> ProjectsSkeleton()
            state.projectCount == 0 -> KitEmptyState(
                art = EmptyArt.Projects,
                message = stringResource(R.string.home_projects_empty),
                action = KitAction(stringResource(R.string.home_new_project), actions.onNewProject),
            )
            else -> {
                Column(Modifier.padding(space.m), verticalArrangement = Arrangement.spacedBy(space.s)) {
                    KitField(
                        value = state.query,
                        onValueChange = actions.onQueryChanged,
                        hint = stringResource(R.string.home_search_hint),
                        keyboard = SEARCH_KEYBOARD,
                    )
                    KitChoice(ProjectSort.entries, state.sort, { sortLabels.getValue(it) }, actions.onSortChanged)
                }
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

@Composable
private fun ProjectSort.label(): String = stringResource(
    when (this) {
        ProjectSort.RECENT -> R.string.home_sort_recent
        ProjectSort.NAME -> R.string.home_sort_name
    },
)

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
    Column(Modifier.padding(Kit.space.l), verticalArrangement = Arrangement.spacedBy(Kit.space.s)) {
        SkeletonBar(bar, Kit.space.l, Modifier.fillMaxWidth(HomeMetrics.SKELETON_TITLE_FRACTION))
        SkeletonBar(bar, Kit.space.m, Modifier.fillMaxWidth(HomeMetrics.SKELETON_SUBTITLE_FRACTION))
    }
}
