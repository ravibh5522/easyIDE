package dev.easyide.app.ui.shell.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.shell.BackContext
import dev.easyide.app.ui.shell.BackStep
import dev.easyide.app.ui.shell.NavItem
import dev.easyide.app.ui.shell.NavTarget
import dev.easyide.app.ui.shell.ShellAction
import dev.easyide.app.ui.shell.host.AdaptiveScaffold
import dev.easyide.app.ui.shell.host.LocalShellActions
import dev.easyide.app.ui.shell.host.LocalShellState
import dev.easyide.app.ui.shell.host.ShellActions
import dev.easyide.app.ui.shell.host.StageCallbacks
import dev.easyide.app.ui.shell.nav.NavBadgeValue
import dev.easyide.app.ui.shell.nav.NavRules
import dev.easyide.app.ui.shell.nav.NavSettings
import dev.easyide.app.ui.shell.nav.NavSurface
import dev.easyide.app.ui.shell.nav.NavSurfaceState

/** The navigation items of the workspace surface and what they wear (the user's settings, the badges). */
class WorkspaceNavState(val items: List<NavItem>, val settings: NavSettings, val badges: Map<String, NavBadgeValue>)

/** What the screen owns that the shell has to reach: commands, the transients Back closes, and the file buffers behind file documents. */
class WorkspaceShellHost(
    val runCommand: (String) -> Unit,
    val notify: (String) -> Unit,
    val transientOpen: Boolean,
    val dismissTransient: () -> Unit,
    val leave: () -> Unit,
    val activateFile: (path: String) -> Unit,
    val closeFile: (path: String) -> Unit,
    val isFileDirty: (path: String) -> Boolean,
)

/** The bars under the body: the status strip, and the input dock when it shows. On a phone the dock replaces the strip and the bottom bar. */
class WorkspaceChrome(val status: @Composable () -> Unit, val dock: (@Composable () -> Unit)?, val statusHidden: Boolean)

/**
 * The workspace-scope shell: the same navigation surface, panels and stage as the app scope's
 * [dev.easyide.app.ui.shell.host.ShellHost], over a workspace [ShellState]. It draws nothing until
 * the model has been fitted to this window, so a wide window never flashes the phone layout. Back
 * goes through the single rule of shell-model.md section 11; leaving the workspace is the caller's.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WorkspaceShell(
    model: WorkspaceShellModel,
    parts: WorkspaceParts,
    nav: WorkspaceNavState,
    slots: WorkspaceSlots,
    host: WorkspaceShellHost,
    chrome: WorkspaceChrome,
    modifier: Modifier = Modifier,
) {
    val window = LocalWindowSize.current
    LaunchedEffect(window) { model.onWindow(window) }
    val state = model.state.collectAsStateWithLifecycle().value ?: return
    if (state.window != window) return

    val focus = remember { FocusRequester() }
    val actions = remember(model, nav.items, host) {
        ShellActions(
            open = { model.open(it) },
            goTo = { id -> nav.items.firstOrNull { it.id == id }?.let(model::select) },
            back = model::closeActive,
            notify = host.notify,
        )
    }
    val stage = remember(model, host) { stageCallbacks(model, host) }
    BackHandler {
        when (model.back(BackContext(transientOpen = host.transientOpen))) {
            BackStep.CLOSE_TRANSIENT -> host.dismissTransient()
            BackStep.LEAVE_WORKSPACE -> host.leave()
            else -> Unit
        }
    }
    LaunchedEffect(state.navFocused) { if (state.navFocused) focus.requestFocus() }

    val placement = NavRules.placement(nav.settings.position, window.width)
    val surface = NavSurfaceState(
        placement, nav.items, state.current.nav, nav.settings.prefs.pinned,
        NavRules.showLabels(nav.settings.labels, placement, window.width), nav.badges, focus,
    )
    val onSelect: (NavItem) -> Unit = { item ->
        when (val target = item.target) {
            is NavTarget.Container -> model.select(item)
            is NavTarget.Command -> host.runCommand(target.id)
        }
    }
    CompositionLocalProvider(LocalShellState provides state, LocalShellActions provides actions) {
        // Test tags become resource ids so on-device checks can find kit ids.
        Box(modifier.fillMaxSize().background(Kit.colors.background).semantics { testTagsAsResourceId = true }) {
            AdaptiveScaffold(placement, nav = { NavSurface(surface, onSelect) }) { area ->
                // The keyboard's inset (or the navigation bar's, when the bottom bar is not there) is taken here, once, so the
                // body, the status strip and the dock all end above it.
                Column(area.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                    WorkspaceBody(state, model, parts, slots, stage, Modifier.weight(1f))
                    chrome.dock?.invoke()
                    if (!chrome.statusHidden) chrome.status()
                }
            }
        }
    }
}

/** The stage's callbacks: a file document is closed and focused through the view model's buffers, everything else through the shell. */
private fun stageCallbacks(model: WorkspaceShellModel, host: WorkspaceShellHost) = StageCallbacks(
    onBack = model::closeActive,
    onActivate = { group, uri ->
        FileDocuments.pathOf(uri)?.let(host.activateFile)
        model.dispatch(ShellAction.Activate(group, uri))
    },
    onKeep = { group, uri -> model.dispatch(ShellAction.Keep(group, uri)) },
    onClose = { group, uri ->
        val path = FileDocuments.pathOf(uri)
        if (path != null) host.closeFile(path) else model.dispatch(ShellAction.Close(group, uri))
    },
    onFocusGroup = { model.dispatch(ShellAction.FocusGroup(it)) },
    onSplit = { model.dispatch(ShellAction.Split) },
    onUnsplit = { model.dispatch(ShellAction.Unsplit(it)) },
    isDirty = { uri -> FileDocuments.pathOf(uri)?.let(host.isFileDirty) == true },
)
