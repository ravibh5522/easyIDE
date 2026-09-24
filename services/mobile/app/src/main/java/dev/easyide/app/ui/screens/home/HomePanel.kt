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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.easyide.app.R
import dev.easyide.app.ui.components.rememberFolderPicker
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.kit.CursorBlock
import dev.easyide.app.ui.kit.CursorStyle
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.app.ui.kit.MotifSurface
import dev.easyide.app.ui.kit.Tone
import dev.easyide.sandbox.external.ExternalFolderSync
import kotlinx.coroutines.delay

/**
 * Home's list, scaffold-free: the shell hosts it in the primary panel (or as the whole screen on
 * a phone). On a phone or a narrow panel it carries the whole "Now" page above the projects; with
 * [showNow] false (a wide window, where [HomeNowPage] fills the stage) it is only the projects.
 *
 * Compose [HomeDialogHost] once wherever Home content can be on screen: dialogs are one question
 * at a time and belong to no single panel.
 *
 * @param selectedProjectId the project whose page is open in the stage, marked in the list.
 * @param showHeader false when the host draws its own title bar and add menu ([HomeAddMenu]).
 */
@Composable
fun HomePanel(
    state: HomeUiState,
    externalFolderSync: ExternalFolderSync,
    callbacks: HomeCallbacks,
    modifier: Modifier = Modifier,
    selectedProjectId: String? = null,
    showNow: Boolean = true,
    showHeader: Boolean = true,
) {
    val nowMs by rememberNow()
    val pickFolder = if (showHeader) rememberFolderPicker(externalFolderSync, callbacks.onFolderPicked, callbacks.onFolderPickFailed) else null
    val actionsFor = rememberProjectActions(callbacks)
    val expanded = LocalWindowSize.current.width.isExpanded
    // Git state and file times move while a workspace or the terminal is open.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { callbacks.onResumed() }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (pickFolder != null) HomeHeader(callbacks, pickFolder)
        HomeMessageBanner(state.message, callbacks.onMessageShown)
        if (showNow) {
            val resumeSelected = expanded && selectedProjectId != null && selectedProjectId == state.resume?.project?.id
            NowSections(state, callbacks, nowMs, resumeSelected)
        }
        ProjectsSection(
            state = state,
            nowMs = nowMs,
            selectedId = selectedProjectId,
            actions = ProjectsActions(
                onQueryChanged = callbacks.onQueryChanged,
                onSortChanged = callbacks.onSortChanged,
                onOpenPage = callbacks.onOpenProjectPage,
                onNewProject = callbacks.onNewProject,
                actionsFor = actionsFor,
            ),
        )
        Spacer(Modifier.height(Kit.space.xxl))
    }
}

/**
 * Resume, Running and Environment as a page of their own, for the stage of a wide window when no
 * project page is open; its hero is the one element marked with crop corners. Content is capped at
 * the readable width and centred. Confirmations show in the panel beside it, so this page has no
 * banner of its own.
 */
@Composable
fun HomeNowPage(state: HomeUiState, callbacks: HomeCallbacks, modifier: Modifier = Modifier) {
    val nowMs by rememberNow()
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Kit.contentMax).fillMaxWidth()) {
            NowSections(state, callbacks, nowMs, resumeSelected = true)
            Spacer(Modifier.height(Kit.space.xxl))
        }
    }
}

/** "easyIDE" with the block cursor after it (blinks [MotifSurface.Home] cycles, then rests solid), and the add menu. */
@Composable
private fun HomeHeader(callbacks: HomeCallbacks, pickFolder: () -> Unit) {
    val space = Kit.space
    Row(
        Modifier.fillMaxWidth().padding(start = space.l, end = space.xs, top = space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.xs),
    ) {
        BasicText(stringResource(R.string.app_name), style = Kit.text.display.copy(color = Kit.colors.plainText))
        CursorBlock(style = CursorStyle.Blinking, maxCycles = MotifSurface.Home.cursorCycles)
        Spacer(Modifier.weight(1f))
        HomeAddMenu(callbacks, pickFolder)
    }
}

/** The "+" of Home: a new project, a folder brought in, or a repository cloned. No floating button. */
@Composable
fun HomeAddMenu(callbacks: HomeCallbacks, pickFolder: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        KitIconButton(Icons.Filled.Add, stringResource(R.string.home_add), { open = true })
        KitMenu(
            expanded = open,
            onDismiss = { open = false },
            items = listOf(
                KitMenuItem.Action(stringResource(R.string.home_new_project), callbacks.onNewProject, Icons.Filled.Add),
                KitMenuItem.Action(stringResource(R.string.home_import_folder), pickFolder, Icons.Filled.CreateNewFolder),
                KitMenuItem.Action(stringResource(R.string.home_clone), { callbacks.onDialog(HomeDialog.Clone) }, Icons.Filled.CloudDownload),
            ),
        )
    }
}

/** What just happened, inline and short-lived: it clears itself after [HomeMetrics.MESSAGE_MS]. */
@Composable
internal fun HomeMessageBanner(message: HomeMessage?, onShown: () -> Unit) {
    if (message == null) return
    LaunchedEffect(message) {
        delay(HomeMetrics.MESSAGE_MS)
        onShown()
    }
    val tone = if (message == HomeMessage.FolderPickFailed) Tone.Danger else Tone.Success
    KitBanner(message.text(), tone = tone, onDismiss = onShown)
}
