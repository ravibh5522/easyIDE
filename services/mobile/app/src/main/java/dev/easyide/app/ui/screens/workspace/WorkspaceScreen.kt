package dev.easyide.app.ui.screens.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import com.termux.view.TerminalView
import dev.easyide.app.ui.commands.CommandPalette
import dev.easyide.app.ui.commands.Keymap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.foundation.HeightClass
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.foundation.MotionTokens
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.ui.foundation.motionSpec
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.sandbox.files.FileNode
import dev.easyide.app.ui.screens.workspace.decor.DecorationRegistry

/** Which naming dialog is open, if any. */
private sealed interface PendingPrompt {
    data class NewFile(val parentDir: String) : PendingPrompt
    data class NewFolder(val parentDir: String) : PendingPrompt
    data class Rename(val node: FileNode) : PendingPrompt
    data class Delete(val node: FileNode) : PendingPrompt
    data class RenameTerminal(val tabId: String, val currentTitle: String) : PendingPrompt
    data class CloseDirtyTab(val tab: EditorTab) : PendingPrompt
    data class LeaveWithUnsaved(val dirtyTabs: List<EditorTab>) : PendingPrompt
}

/**
 * The IDE screen: activity rail, explorer, tabbed editor, terminals, status bar,
 * separated by 1dp rules so the panes read as distinct surfaces.
 */
@Composable
fun WorkspaceScreen(
    projectName: String,
    uiState: WorkspaceUiState,
    callbacks: WorkspaceCallbacks,
    gitState: GitPanelState,
    gitCallbacks: SourceControlCallbacks,
    decorations: DecorationRegistry,
    modifier: Modifier = Modifier,
) {
    val onRefreshGit = gitCallbacks.onRefresh
    val windowSize = LocalWindowSize.current
    val colors = editorColors
    val stages = rememberWorkspaceStageState(windowSize)
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    var menuNode by remember { mutableStateOf<FileNode?>(null) }
    var prompt by remember { mutableStateOf<PendingPrompt?>(null) }

    LaunchedEffect(uiState.statusMessage) {
        val message = uiState.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        callbacks.onStatusShown()
    }

    var sidePanel by rememberSaveable { mutableStateOf(SidePanel.EXPLORER) }
    var paletteOpen by rememberSaveable { mutableStateOf(false) }

    // Leaving clears the ViewModel and with it every buffer, so dirty tabs
    // must be saved or explicitly discarded first. System back and the rail's
    // Back arrow both route through here.
    val dirtyTabs = uiState.openTabs.filter { it.isDirty }
    val requestLeave: () -> Unit = {
        if (dirtyTabs.isEmpty()) callbacks.onBack() else prompt = PendingPrompt.LeaveWithUnsaved(dirtyTabs)
    }
    BackHandler(enabled = dirtyTabs.isNotEmpty(), onBack = requestLeave)
    val requestCloseTab: (String) -> Unit = { path ->
        val tab = uiState.openTabs.find { it.relativePath == path }
        if (tab?.isDirty == true) prompt = PendingPrompt.CloseDirtyTab(tab) else callbacks.onTabClosed(path)
    }

    // Clicking the active destination collapses the panel; clicking the other
    // one switches to it, which is how every rail of this shape behaves.
    val toggleExplorer = {
        if (sidePanel == SidePanel.EXPLORER) stages.toggleLeft(exclusive = false)
        else { sidePanel = SidePanel.EXPLORER; stages.showLeft() }
    }
    val toggleSourceControl = {
        if (sidePanel == SidePanel.SOURCE_CONTROL) stages.toggleLeft(exclusive = false)
        else { sidePanel = SidePanel.SOURCE_CONTROL; stages.showLeft(); onRefreshGit() }
    }
    val commands = workspaceCommands(
        uiState = uiState,
        callbacks = callbacks,
        shell = WorkspaceShellActions(
            toggleExplorer = toggleExplorer,
            toggleSourceControl = toggleSourceControl,
            toggleTerminal = stages::toggleBottom,
            showCommands = { paletteOpen = true },
            closeTab = requestCloseTab,
        ),
    )
    val keymap = Keymap.DEFAULT
    val hostView = LocalView.current
    val rootFocus = remember { FocusRequester() }

    // Shortcuts need a focused node to be delivered at all, so the root takes
    // focus when nothing else holds it - but never from the terminal, which
    // grabs focus on attach so typing on a hardware keyboard goes to the shell.
    LaunchedEffect(Unit) {
        if (hostView.rootView.findFocus() == null) rootFocus.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .onPreviewKeyEvent { event ->
                // A focused terminal dispatches its own keys (see
                // EasyTerminalViewClient.onHardwareKey); handling them here as
                // well would run a command twice or steal a shell chord.
                if (hostView.rootView.findFocus() is TerminalView) return@onPreviewKeyEvent false
                keymap.dispatch(event.nativeKeyEvent, terminalFocused = false, commands)
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        Column(
            // imePadding after systemBars: the keyboard's inset minus the nav bar
            // already consumed, so the whole layout (editor caret, terminal, key
            // row, status bar) resizes above the keyboard instead of under it.
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding(),
        ) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                ActivityBar(
                    explorerVisible = stages.leftVisible && sidePanel == SidePanel.EXPLORER,
                    sourceControlVisible = stages.leftVisible && sidePanel == SidePanel.SOURCE_CONTROL,
                    terminalVisible = stages.bottomVisible,
                    onToggleExplorer = toggleExplorer,
                    onToggleSourceControl = toggleSourceControl,
                    onToggleTerminal = stages::toggleBottom,
                    onShowCommands = { paletteOpen = true },
                    onBack = requestLeave,
                )
                VerticalDivider()

                Box(modifier = Modifier.fillMaxHeight().weight(1f)) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        if (!windowSize.width.isCompact) {
                            ExplorerColumn(
                                visible = stages.leftVisible,
                                width = windowSize.width.explorerWidth(),
                                uiState = uiState,
                                callbacks = callbacks,
                                sidePanel = sidePanel,
                                gitState = gitState,
                                gitCallbacks = gitCallbacks,
                                onNodeMenu = { menuNode = it },
                                onNewFile = { prompt = PendingPrompt.NewFile("") },
                                onNewFolder = { prompt = PendingPrompt.NewFolder("") },
                            )
                        }

                        Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
                            EditorTabBar(
                                tabs = uiState.openTabs,
                                activeTabPath = uiState.activeTabPath,
                                onTabSelected = callbacks.onTabSelected,
                                onTabClosed = requestCloseTab,
                                onTogglePreview = callbacks.onTogglePreview,
                            )
                            HorizontalDividerLine()

                            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                EditorPane(
                                    tab = uiState.activeTab,
                                    onContentChanged = { content ->
                                        uiState.activeTabPath?.let { callbacks.onContentChanged(it, content) }
                                    },
                                    decorations = uiState.activeTabPath?.let(decorations::model),
                                )
                            }

                            TerminalDock(visible = stages.bottomVisible) {
                                Column {
                                    HorizontalDividerLine()
                                    TerminalPane(
                                        tabs = uiState.terminals,
                                        activeTabId = uiState.activeTerminalId,
                                        linuxReady = uiState.linuxReady,
                                        isInstalling = uiState.isInstalling,
                                        onNewTab = callbacks.onNewTerminal,
                                        onSelectTab = callbacks.onSelectTerminal,
                                        onCloseTab = callbacks.onCloseTerminal,
                                        onRenameTab = { id, title -> prompt = PendingPrompt.RenameTerminal(id, title) },
                                        onInstallLinux = callbacks.onInstallLinux,
                                        onHardwareKey = { e -> keymap.dispatch(e, terminalFocused = true, commands) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(windowSize.height.terminalHeight()),
                                    )
                                }
                            }
                        }
                    }

                    if (windowSize.width.isCompact) {
                        ExplorerOverlay(
                            visible = stages.leftVisible,
                            width = windowSize.width.explorerWidth(),
                            uiState = uiState,
                            callbacks = callbacks,
                            sidePanel = sidePanel,
                            gitState = gitState,
                            gitCallbacks = gitCallbacks,
                            onNodeMenu = { menuNode = it },
                            onNewFile = { prompt = PendingPrompt.NewFile("") },
                            onNewFolder = { prompt = PendingPrompt.NewFolder("") },
                        )
                    }
                }
            }

            StatusBar(
                projectName = projectName,
                branch = gitState.status?.branch,
                activeTab = uiState.activeTab,
                onSave = callbacks.onSave,
            )
        }

        FileContextMenu(
            node = menuNode,
            canPaste = uiState.clipboard != null,
            onDismiss = { menuNode = null },
            onAction = { action, node ->
                menuNode = null
                when (action) {
                    FileAction.NEW_FILE -> prompt = PendingPrompt.NewFile(node.relativePath)
                    FileAction.NEW_FOLDER -> prompt = PendingPrompt.NewFolder(node.relativePath)
                    FileAction.RENAME -> prompt = PendingPrompt.Rename(node)
                    FileAction.DELETE -> prompt = PendingPrompt.Delete(node)
                    FileAction.COPY -> callbacks.onCopyToClipboard(node, false)
                    FileAction.CUT -> callbacks.onCopyToClipboard(node, true)
                    FileAction.PASTE -> callbacks.onPaste(node.relativePath)
                    FileAction.COPY_PATH ->
                        clipboard.setText(AnnotatedString(callbacks.absolutePathOf(node)))
                    FileAction.COPY_RELATIVE_PATH ->
                        clipboard.setText(AnnotatedString(node.relativePath))
                }
            },
        )

        PromptDialogs(prompt, callbacks) { prompt = null }

        if (paletteOpen) {
            CommandPalette(registry = commands, keymap = keymap, onDismiss = { paletteOpen = false })
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = SNACKBAR_BOTTOM_PADDING_DP.dp),
        )
    }
}

/**
 * The bottom terminal stage, kept composed while hidden.
 *
 * It used to sit in an AnimatedVisibility that expanded and shrank it: that
 * resized the editor above it on every frame of the animation (a full relayout
 * of the buffer's text each time) and disposed the TerminalView on hide, so each
 * toggle rebuilt its renderer and re-attached the session. Now the size snaps
 * and only alpha animates. Hidden, the dock is still measured at full height -
 * the terminal grid keeps its rows and the shell gets no resize - but reports
 * zero height and is not placed, so it neither draws nor takes touches.
 */
@Composable
private fun TerminalDock(visible: Boolean, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, motionSpec(), label = "terminalDock")
    val focusManager = LocalFocusManager.current
    var hasFocus by remember { mutableStateOf(false) }
    // A hidden terminal must not keep the keyboard and swallow typing.
    LaunchedEffect(visible) { if (!visible && hasFocus) focusManager.clearFocus(force = true) }

    Box(
        modifier = Modifier
            .onFocusChanged { hasFocus = it.hasFocus }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                if (visible) layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                else layout(placeable.width, 0) {}
            }
            .graphicsLayer { this.alpha = alpha },
    ) {
        content()
    }
}

@Composable
private fun PromptDialogs(
    prompt: PendingPrompt?,
    callbacks: WorkspaceCallbacks,
    onDismiss: () -> Unit,
) {
    when (prompt) {
        null -> Unit

        is PendingPrompt.NewFile -> NameInputDialog(
            title = "New file",
            initialValue = "",
            confirmLabel = "Create",
            onConfirm = { name -> callbacks.onCreateFile(prompt.parentDir, name); onDismiss() },
            onDismiss = onDismiss,
        )

        is PendingPrompt.NewFolder -> NameInputDialog(
            title = "New folder",
            initialValue = "",
            confirmLabel = "Create",
            onConfirm = { name -> callbacks.onCreateFolder(prompt.parentDir, name); onDismiss() },
            onDismiss = onDismiss,
        )

        is PendingPrompt.Rename -> NameInputDialog(
            title = "Rename",
            initialValue = prompt.node.name,
            confirmLabel = "Rename",
            onConfirm = { name -> callbacks.onRename(prompt.node, name); onDismiss() },
            onDismiss = onDismiss,
        )

        is PendingPrompt.Delete -> ConfirmDeleteDialog(
            node = prompt.node,
            onConfirm = { callbacks.onDelete(prompt.node); onDismiss() },
            onDismiss = onDismiss,
        )

        is PendingPrompt.RenameTerminal -> NameInputDialog(
            title = "Rename terminal",
            initialValue = prompt.currentTitle,
            confirmLabel = "Rename",
            onConfirm = { name -> callbacks.onRenameTerminal(prompt.tabId, name); onDismiss() },
            onDismiss = onDismiss,
        )

        is PendingPrompt.CloseDirtyTab -> {
            val path = prompt.tab.relativePath
            UnsavedChangesDialog(
                fileName = prompt.tab.name,
                fileCount = 1,
                onSave = { onDismiss(); callbacks.onSaveTabs(listOf(path)) { callbacks.onTabClosed(path) } },
                onDiscard = { onDismiss(); callbacks.onTabClosed(path) },
                onDismiss = onDismiss,
            )
        }

        is PendingPrompt.LeaveWithUnsaved -> UnsavedChangesDialog(
            fileName = prompt.dirtyTabs.singleOrNull()?.name,
            fileCount = prompt.dirtyTabs.size,
            onSave = { onDismiss(); callbacks.onSaveTabs(prompt.dirtyTabs.map { it.relativePath }, callbacks.onBack) },
            onDiscard = { onDismiss(); callbacks.onBack() },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun RowScope.ExplorerColumn(
    visible: Boolean,
    width: Dp,
    uiState: WorkspaceUiState,
    callbacks: WorkspaceCallbacks,
    sidePanel: SidePanel,
    gitState: GitPanelState,
    gitCallbacks: SourceControlCallbacks,
    onNodeMenu: (FileNode) -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            animationSpec = motionSpec(easing = MotionTokens.EnterEasing),
            initialOffsetX = { -it },
        ) + fadeIn(motionSpec()),
        exit = slideOutHorizontally(animationSpec = motionSpec(), targetOffsetX = { -it }) +
            fadeOut(motionSpec()),
    ) {
        Row {
            when (sidePanel) {
                SidePanel.EXPLORER -> FileTreePane(
                    state = uiState,
                    onFileOpened = callbacks.onFileOpened,
                    onDirectoryToggled = callbacks.onDirectoryToggled,
                    onNodeMenu = onNodeMenu,
                    onNewFile = onNewFile,
                    onNewFolder = onNewFolder,
                    onRefresh = callbacks.onRefreshTree,
                    modifier = Modifier.width(width).fillMaxHeight(),
                )
                SidePanel.SOURCE_CONTROL -> SourceControlPane(
                    state = gitState,
                    callbacks = gitCallbacks,
                    modifier = Modifier.width(width).fillMaxHeight(),
                )
            }
            VerticalDivider()
        }
    }
}

/** Compact-width explorer, drawn over the editor. */
@Composable
private fun ExplorerOverlay(
    visible: Boolean,
    width: Dp,
    uiState: WorkspaceUiState,
    callbacks: WorkspaceCallbacks,
    sidePanel: SidePanel,
    gitState: GitPanelState,
    gitCallbacks: SourceControlCallbacks,
    onNodeMenu: (FileNode) -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            animationSpec = motionSpec(easing = MotionTokens.EnterEasing),
            initialOffsetX = { -it },
        ) + fadeIn(motionSpec()),
        exit = slideOutHorizontally(animationSpec = motionSpec(), targetOffsetX = { -it }) +
            fadeOut(motionSpec()),
    ) {
        Row {
            when (sidePanel) {
                SidePanel.EXPLORER -> FileTreePane(
                    state = uiState,
                    onFileOpened = callbacks.onFileOpened,
                    onDirectoryToggled = callbacks.onDirectoryToggled,
                    onNodeMenu = onNodeMenu,
                    onNewFile = onNewFile,
                    onNewFolder = onNewFolder,
                    onRefresh = callbacks.onRefreshTree,
                    modifier = Modifier.width(width).fillMaxHeight(),
                )
                SidePanel.SOURCE_CONTROL -> SourceControlPane(
                    state = gitState,
                    callbacks = gitCallbacks,
                    modifier = Modifier.width(width).fillMaxHeight(),
                )
            }
            VerticalDivider()
        }
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .width(DIVIDER_DP.dp)
            .fillMaxHeight()
            .background(editorColors.panelBorder),
    )
}

@Composable
private fun HorizontalDividerLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DIVIDER_DP.dp)
            .background(editorColors.panelBorder),
    )
}

private fun WidthClass.explorerWidth(): Dp = when (this) {
    WidthClass.COMPACT -> COMPACT_EXPLORER_DP.dp
    WidthClass.MEDIUM -> MEDIUM_EXPLORER_DP.dp
    WidthClass.EXPANDED -> EXPANDED_EXPLORER_DP.dp
}

private fun HeightClass.terminalHeight(): Dp =
    if (this == HeightClass.COMPACT) COMPACT_TERMINAL_DP.dp else REGULAR_TERMINAL_DP.dp

private const val COMPACT_EXPLORER_DP = 240
private const val MEDIUM_EXPLORER_DP = 240
private const val EXPANDED_EXPLORER_DP = 280
private const val COMPACT_TERMINAL_DP = 160
private const val REGULAR_TERMINAL_DP = 260
private const val SNACKBAR_BOTTOM_PADDING_DP = 48
private const val DIVIDER_DP = 1
