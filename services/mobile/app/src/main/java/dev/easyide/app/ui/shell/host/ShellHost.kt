package dev.easyide.app.ui.shell.host

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.app.ui.commands.KeyChord
import dev.easyide.app.ui.foundation.LocalKeymap
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.screens.workspace.layout.Pane
import dev.easyide.app.ui.shell.BackNavigation
import dev.easyide.app.ui.shell.BackStep
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.nav.NavRules
import dev.easyide.app.ui.shell.nav.NavSurface
import dev.easyide.app.ui.shell.nav.NavSurfaceState

/**
 * The app-scope shell: navigation surface, primary panel and stage, driven by [shell]. It draws
 * nothing until the shell has restored and fitted itself to this window, so a wide window never
 * flashes the phone layout. Back is handled here through the single rule in `BackNavigation`; the
 * system takes it back (and the app leaves) only when the shell has nothing left to do. [dialogs] is
 * where dialogs that belong to no one panel are mounted, once (Home's, the extensions').
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ShellHost(
    shell: ShellViewModel,
    panels: PanelRendererRegistry,
    renderers: DocumentRendererRegistry,
    modifier: Modifier = Modifier,
    dialogs: @Composable () -> Unit = {},
) {
    val window = LocalWindowSize.current
    LaunchedEffect(window) { shell.onWindow(window) }
    val state = shell.state.collectAsStateWithLifecycle().value ?: return
    if (state.window != window) return

    val registries by shell.effective.collectAsStateWithLifecycle()
    val items by shell.navItems.collectAsStateWithLifecycle()
    val settings by shell.navSettings.collectAsStateWithLifecycle()
    val badges by shell.navBadges.collectAsStateWithLifecycle()
    val toast by shell.toast.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val actions = remember(shell) { ShellActions(open = { shell.open(it) }, goTo = shell::goTo, back = { shell.back() }, notify = shell::notify) }
    val keymap = LocalKeymap.current

    val handlesBack = remember(state) { BackNavigation.back(state).step != BackStep.SYSTEM }
    BackHandler(enabled = handlesBack) { shell.back() }
    LaunchedEffect(state.navFocused) { if (state.navFocused) focus.requestFocus() }

    val placement = NavRules.placement(settings.position, window.width)
    val surface = NavSurfaceState(
        placement, items, state.current.nav, settings.prefs.pinned,
        NavRules.showLabels(settings.labels, placement, window.width), badges, focus,
    )
    val callbacks = StageCallbacks(
        onBack = { shell.back() },
        onActivate = { _, uri -> shell.activate(uri) },
        onKeep = { _, uri -> shell.keep(uri) },
        onClose = { _, uri -> shell.close(uri) },
    )
    CompositionLocalProvider(LocalShellState provides state, LocalShellActions provides actions) {
        Box(
            modifier.fillMaxSize().background(Kit.colors.background)
                // Test tags become resource ids so the baseline profile generator and ui-device-check can find kit ids.
                .semantics { testTagsAsResourceId = true }
                .onPreviewKeyEvent { event ->
                val toggle = event.type == KeyEventType.KeyDown && !state.compact &&
                    keymap.commandFor(KeyChord.of(event.nativeKeyEvent), terminalFocused = false) == CommandIds.TOGGLE_EXPLORER
                if (toggle) shell.togglePanel(Placement.SIDEBAR)
                toggle
            },
        ) {
            AdaptiveScaffold(placement, nav = { NavSurface(surface, shell::selectNav) }) { area ->
                Box(area) {
                    ShellBody(
                        state, registries.containers, panels,
                        onResizePanel = { shell.resizePane(Pane.EXPLORER, it) },
                        stage = { m, idle -> StageHost(state, registries.documents, renderers, callbacks, m, idle) },
                    )
                    ToastHost(toast, shell::toastDismissed, Modifier.align(Alignment.BottomCenter))
                }
            }
            dialogs()
        }
    }
}
