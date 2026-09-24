package dev.easyide.app.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import dev.easyide.app.R
import dev.easyide.app.ui.components.rememberFolderPicker
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitScaffold
import dev.easyide.sandbox.external.ExternalFolderSync

/**
 * Home as one destination with its own title bar, for as long as the navigation graph still hosts
 * it as a screen: [HomePanel] and [ProjectPage] composed the way the shell will compose them. At
 * expanded width the projects sit beside the stage (the "Now" page, or the project last tapped);
 * anywhere narrower it is one column and a tapped project opens its page as a screen of its own
 * (Back returns to the list). Once the shell hosts [HomePanel], [HomeNowPage] and [ProjectPage]
 * directly this composable has no caller and goes.
 */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    externalFolderSync: ExternalFolderSync,
    callbacks: HomeCallbacks,
    modifier: Modifier = Modifier,
) {
    val expanded = LocalWindowSize.current.width.isExpanded
    val selected = uiState.selected?.takeIf { uiState.detailOpen }
    val pickFolder = rememberFolderPicker(externalFolderSync, callbacks.onFolderPicked, callbacks.onFolderPickFailed)
    BackHandler(enabled = selected != null, onBack = callbacks.onCloseDetail)

    KitScaffold(
        title = selected?.project?.name ?: stringResource(R.string.nav_home),
        modifier = modifier,
        onBack = if (selected != null) callbacks.onCloseDetail else null,
        actions = {
            if (selected == null) HomeAddMenu(callbacks, pickFolder)
            KitIconButton(Icons.Filled.Settings, stringResource(R.string.nav_settings), callbacks.onOpenSettings)
        },
        maxContentWidth = if (expanded) Dp.Unspecified else Kit.contentMax,
    ) {
        val stage: @Composable () -> Unit = {
            if (selected != null) ProjectPage(selected.project.id, uiState, callbacks) else HomeNowPage(uiState, callbacks)
        }
        when {
            expanded -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(HomeMetrics.LIST_PANE_WEIGHT).widthIn(min = HomeMetrics.listPaneMinWidth, max = HomeMetrics.listPaneMaxWidth)) {
                    HomePanel(uiState, externalFolderSync, callbacks, selectedProjectId = selected?.project?.id, showNow = false, showHeader = false)
                }
                Box(Modifier.fillMaxHeight().width(Kit.hairline).background(Kit.colors.panelBorder))
                Box(Modifier.weight(HomeMetrics.STAGE_WEIGHT).fillMaxHeight()) { stage() }
            }
            selected != null -> stage()
            else -> HomePanel(uiState, externalFolderSync, callbacks, showHeader = false)
        }
    }
    HomeDialogHost(uiState, callbacks)
}
