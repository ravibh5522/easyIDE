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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import dev.easyide.app.R
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.ui.foundation.LocalSettings
import androidx.core.content.res.ResourcesCompat
import dev.easyide.app.ui.theme.ControlSize
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.extensions.contrib.RowKey

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
    onHardwareKey: (android.view.KeyEvent) -> Boolean,
    modifier: Modifier = Modifier,
    /** The active key row's keys (built-in `builtin.terminal` unless a pack's row applies). */
    rowKeys: List<RowKey> = emptyList(),
    onRowKey: (KeyAction, com.termux.terminal.TerminalSession) -> Unit = { _, _ -> },
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
            onHardwareKey = onHardwareKey,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        // Writing to a TerminalSession is a plain, synchronous, non-suspending
        // queue push - no ViewModel round trip needed, the same way a tap
        // inside MermaidView's WebView does not need one either.
        if (rowKeys.isNotEmpty()) KeyRowBar(keys = rowKeys, onKey = { action -> onRowKey(action, tab.session) })
    }
}

/**
 * Hosts a single [TerminalView] (a plain Android `View`, wrapped the same way
 * [MermaidView] wraps a `WebView`) and keeps it attached to whichever tab is
 * active. Text size is set in the factory too - the view needs it before its
 * first real layout pass, since `attachSession()` calls `updateSize()`
 * immediately and that dereferences the renderer - and again in `update` when
 * the `terminal.fontSize` setting changes.
 */
@Composable
private fun EasyTerminalView(
    tab: PtyTerminalTab,
    onHardwareKey: (android.view.KeyEvent) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fontSp = LocalSettings.current[SettingsSchema.terminalFontSize]
    val textSizePx = with(density) { fontSp.sp.roundToPx() }
    // setTextSize() rebuilds the renderer and resizes the emulator, so it runs
    // only on a real change, not on every recomposition that re-runs update.
    val appliedTextSize = remember { AppliedTextSize(textSizePx) }
    val client = remember { EasyTerminalViewClient() }
    val palette = editorColors.terminal

    AndroidView(
        factory = { context ->
            TerminalView(context, null).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setTerminalViewClient(client)
                setTextSize(textSizePx)
                // After setTextSize: setTypeface rebuilds the renderer it creates.
                ResourcesCompat.getFont(context, R.font.geist_mono_regular)?.let(::setTypeface)
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
            client.onHardwareKey = onHardwareKey
            if (appliedTextSize.px != textSizePx) {
                appliedTextSize.px = textSizePx
                view.setTextSize(textSizePx)
                view.invalidate()
            }
            // Before attach, so an emulator created by this attach already
            // copies the themed defaults; an existing one is re-coloured.
            if (TerminalTheme.apply(palette, tab.session.emulator)) view.invalidate()
            // attachSession() itself no-ops (returns false) when already
            // attached to this exact session, so this only steals focus on a
            // real tab switch, not on every unrelated recomposition.
            if (view.attachSession(tab.session)) view.requestFocus()
        },
        // TerminalView ignores Android View padding entirely (it lays out
        // columns from measured width, not padding-adjusted width), so the
        // margin has to come from Compose padding around it instead - with a
        // matching background so the margin reads as inset, not a mismatched
        // strip next to the terminal's own canvas.
        modifier = modifier
            .background(palette.background)
            .padding(horizontal = Spacing.s),
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
                    .height(ControlSize.tab)
                    .background(if (active) colors.tabActive else colors.tabInactive)
                    .tabAccentBar(active, colors.tabActiveBorder)
                    .combinedClickable(
                        onClick = { onSelectTab(tab.id) },
                        onLongClick = { onRenameTab(tab.id, tab.title) },
                    )
                    .padding(horizontal = Spacing.m),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Text(
                    text = tab.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) colors.tabActiveText else colors.tabInactiveText,
                )
                if (tabs.size > 1) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close ${tab.title}",
                        tint = colors.textMuted,
                        modifier = Modifier
                            .size(IconSize.xs)
                            .clickable { onCloseTab(tab.id) },
                    )
                }
            }
        }

        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = stringResource(R.string.terminal_new),
            tint = colors.textMuted,
            modifier = Modifier
                .padding(horizontal = Spacing.s)
                .size(IconSize.s)
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
                contentPadding = PaddingValues(horizontal = Spacing.s, vertical = Spacing.none),
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

/** Last text size handed to the view; plain holder, never observed by Compose. */
private class AppliedTextSize(var px: Int)
