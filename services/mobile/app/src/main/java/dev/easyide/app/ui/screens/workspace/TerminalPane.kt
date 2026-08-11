package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import dev.easyide.app.R
import dev.easyide.app.ui.theme.editorColors

/**
 * Terminal panel: tabs across the top, a real pty-backed terminal below, and
 * an accessory key row pinned to the bottom.
 *
 * "Real" here means [TerminalView] (from the vendored `terminal-view`
 * library) owns the whole screen - cursor, scrollback, ANSI colors, full
 * -screen programs like `vim` or `htop` - the way a genuine terminal
 * emulator does. There is no separate input row: typing happens wherever the
 * shell's own cursor is, exactly like a desktop terminal, because this
 * *is* one now rather than a scrolling list of captured output lines.
 */
@Composable
fun TerminalPane(
    tabs: List<PtyTerminalTab>,
    activeTabId: String?,
    linuxReady: Boolean,
    isInstalling: Boolean,
    onNewTab: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onRenameTab: (String, String) -> Unit,
    onInstallLinux: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = editorColors
    val tab = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        TerminalTabBar(
            tabs = tabs,
            activeTabId = tab?.id,
            linuxReady = linuxReady,
            isInstalling = isInstalling,
            onSelectTab = onSelectTab,
            onCloseTab = onCloseTab,
            onRenameTab = onRenameTab,
            onNewTab = onNewTab,
            onInstallLinux = onInstallLinux,
        )

        if (tab == null) return@Column

        EasyTerminalView(
            tab = tab,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        // Writing to a TerminalSession is a plain, synchronous, non-suspending
        // queue push - no ViewModel round trip needed, the same way a tap
        // inside MermaidView's WebView does not need one either.
        TerminalKeyRow(onKey = { key -> tab.session.write(key.bytes) })
    }
}

/**
 * Hosts a single [TerminalView] (a plain Android `View`, wrapped the same way
 * [MermaidView] wraps a `WebView`) and keeps it attached to whichever tab is
 * active. Text size is set once up front - the view needs it before its first
 * real layout pass, since `attachSession()` calls `updateSize()` immediately
 * and that dereferences the renderer.
 */
@Composable
private fun EasyTerminalView(tab: PtyTerminalTab, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val textSizePx = remember(density) { with(density) { TERMINAL_FONT_SP.sp.roundToPx() } }
    val client = remember { EasyTerminalViewClient() }
    val colors = editorColors

    AndroidView(
        factory = { context ->
            TerminalView(context, null).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setTerminalViewClient(client)
                setTextSize(textSizePx)
                // The client raises the soft keyboard on tap and needs the view
                // to do it; the view does not hand itself to the client.
                client.terminalView = this
            }
        },
        update = { view ->
            // TerminalView does not redraw itself when the session's screen
            // buffer changes - it has to be told to. Reassigned on every
            // recomposition (idempotent) rather than only on attach, so this
            // stays correct if Compose ever recreates the AndroidView.
            tab.client.onScreenChanged = { view.onScreenUpdated(); view.invalidate() }
            // attachSession() itself no-ops (returns false) when already
            // attached to this exact session, so this only steals focus on a
            // real tab switch, not on every unrelated recomposition.
            if (view.attachSession(tab.session)) view.requestFocus()
        },
        // TerminalView ignores Android View padding entirely (it lays out
        // columns from measured width, not padding-adjusted width), so the
        // margin has to come from Compose padding around it instead - with a
        // matching background so the margin reads as inset, not a mismatched
        // strip next to the terminal's own black canvas.
        modifier = modifier
            .background(colors.terminalBackground)
            .padding(horizontal = TERMINAL_HORIZONTAL_PADDING_DP.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalTabBar(
    tabs: List<PtyTerminalTab>,
    activeTabId: String?,
    linuxReady: Boolean,
    isInstalling: Boolean,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onRenameTab: (String, String) -> Unit,
    onNewTab: () -> Unit,
    onInstallLinux: () -> Unit,
) {
    val colors = editorColors

    Row(
        modifier = Modifier.fillMaxWidth().background(colors.panel),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        // Tabs scroll; the install action stays pinned. weight() cannot live
        // inside the scrolling row - its width constraint is unbounded there.
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
        tabs.forEach { tab ->
            val active = tab.id == activeTabId
            Row(
                modifier = Modifier
                    .background(if (active) colors.tabActive else colors.panel)
                    .combinedClickable(
                        onClick = { onSelectTab(tab.id) },
                        onLongClick = { onRenameTab(tab.id, tab.title) },
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = tab.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) colors.plainText else colors.gutterText,
                )
                if (tabs.size > 1) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close ${tab.title}",
                        tint = colors.gutterText,
                        modifier = Modifier
                            .size(TAB_ICON_DP.dp)
                            .clickable { onCloseTab(tab.id) },
                    )
                }
            }
        }

        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = stringResource(R.string.terminal_new),
            tint = colors.gutterText,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .size(TAB_ICON_DP.dp)
                .clickable(onClick = onNewTab),
        )
        }

        if (!linuxReady) {
            // Install progress prints as real scrolling output into the
            // terminal tab itself (see WorkspaceViewModel.appendInstallLog) -
            // this button is just the start action plus a busy indicator.
            TextButton(
                onClick = onInstallLinux,
                enabled = !isInstalling,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    text = if (isInstalling) {
                        stringResource(R.string.terminal_installing)
                    } else {
                        stringResource(R.string.terminal_install_linux)
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private const val TAB_ICON_DP = 14
private const val TERMINAL_FONT_SP = 13
private const val TERMINAL_HORIZONTAL_PADDING_DP = 8
