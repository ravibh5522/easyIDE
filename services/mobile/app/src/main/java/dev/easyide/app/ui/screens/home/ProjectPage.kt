package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitMenu

/**
 * The document `easyide://project/<id>`: who the project is, how to go in, and what is known about
 * it without opening it. Scaffold-free; the shell owns the title and Back. [projectId] is plain
 * text so the shell can pass it straight from the address.
 *
 * Compose [HomeDialogHost] once beside it: rename, duplicate, delete and change environment ask
 * their question there. Confirmations show inline here unless the window is wide enough for the
 * panel to show them ([showMessage]).
 */
@Composable
fun ProjectPage(
    projectId: String,
    state: HomeUiState,
    callbacks: HomeCallbacks,
    modifier: Modifier = Modifier,
    showMessage: Boolean = !LocalWindowSize.current.width.isExpanded,
) {
    val item = state.find(projectId)
    val nowMs by rememberNow()
    // The view model reads recent files for the project in focus; a page names its own.
    LaunchedEffect(projectId) { callbacks.onSelect(projectId) }

    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Kit.contentMax).fillMaxWidth()) {
            if (showMessage) HomeMessageBanner(state.message, callbacks.onMessageShown)
            when {
                item != null -> ProjectPageBody(item, state, callbacks, nowMs)
                !state.isLoading -> KitEmptyState(EmptyArt.Search, stringResource(R.string.home_page_missing))
            }
            Spacer(Modifier.height(Kit.space.xxl))
        }
    }
}

@Composable
private fun ProjectPageBody(item: ProjectListItem, state: HomeUiState, callbacks: HomeCallbacks, nowMs: Long) {
    val space = Kit.space
    val actions = rememberProjectActions(callbacks)(item)
    val onStop = rememberStopRequest(callbacks)
    var menuOpen by remember { mutableStateOf(false) }
    val sessions = state.running.filter { it.projectId == item.project.id }

    Row(
        Modifier.fillMaxWidth().padding(start = space.l, end = space.xs, top = space.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.m),
    ) {
        ProjectMonogram(item.project.name, HomeMetrics.monogramPage)
        Column(Modifier.weight(1f)) {
            BasicText(
                item.project.name,
                Modifier.semantics { heading() },
                style = Kit.type.headlineMedium.copy(color = Kit.colors.plainText),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MonoText(item.locationLabel())
        }
        Box {
            KitIconButton(Icons.Filled.MoreVert, stringResource(R.string.home_page_more), { menuOpen = true })
            KitMenu(menuOpen, { menuOpen = false }, projectMenuItems(actions))
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = space.l, vertical = space.m), horizontalArrangement = Arrangement.spacedBy(space.s)) {
        KitButton(
            stringResource(R.string.home_page_open),
            { callbacks.onOpenProject(item, false) },
            Modifier.weight(1f).kitTag(HomeMetrics.PROJECT_OPEN_ID),
            icon = Icons.Filled.FolderOpen,
        )
        KitButton(
            stringResource(R.string.home_page_open_terminal),
            { callbacks.onOpenProject(item, true) },
            Modifier.weight(1f),
            style = KitButtonStyle.Secondary,
            icon = Icons.Filled.Terminal,
        )
    }
    ProjectDetailsSection(item, state.projectsIn(item.project.environmentId), nowMs)
    RecentFilesSection(state.recentFilesOf(item.project.id), nowMs)
    RunningSection(
        title = stringResource(R.string.home_page_section_sessions),
        items = sessions,
        onAttach = { attachRunning(state, callbacks, it) },
        onStop = onStop,
    )
}
