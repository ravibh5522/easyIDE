package dev.easyide.app.ui.screens.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
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

/** Which naming dialog is open, if any. */
private sealed interface PendingPrompt {
    data class NewFile(val parentDir: String) : PendingPrompt
    data class NewFolder(val parentDir: String) : PendingPrompt
    data class Rename(val node: FileNode) : PendingPrompt
    data class Delete(val node: FileNode) : PendingPrompt
    data class RenameTerminal(val tabId: String, val currentTitle: String) : PendingPrompt
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
    modifier: Modifier = Modifier,
) {
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

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                ActivityBar(
                    explorerVisible = stages.leftVisible,
                    terminalVisible = stages.bottomVisible,
                    onToggleExplorer = { stages.toggleLeft(exclusive = false) },
                    onToggleTerminal = stages::toggleBottom,
                    onBack = callbacks.onBack,
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
                                onTabClosed = callbacks.onTabClosed,
                                onTogglePreview = callbacks.onTogglePreview,
                            )
                            HorizontalDividerLine()

                            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                EditorPane(
                                    tab = uiState.activeTab,
                                    onContentChanged = { content ->
                                        uiState.activeTabPath?.let { callbacks.onContentChanged(it, content) }
                                    },
                                )
                            }

                            AnimatedVisibility(
                                visible = stages.bottomVisible,
                                enter = expandVertically(motionSpec()) + fadeIn(motionSpec()),
                                exit = shrinkVertically(motionSpec()) + fadeOut(motionSpec()),
                            ) {
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
                            onNodeMenu = { menuNode = it },
                            onNewFile = { prompt = PendingPrompt.NewFile("") },
                            onNewFolder = { prompt = PendingPrompt.NewFolder("") },
                        )
                    }
                }
            }

            StatusBar(
                projectName = projectName,
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = SNACKBAR_BOTTOM_PADDING_DP.dp),
        )
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
    }
}

@Composable
private fun RowScope.ExplorerColumn(
    visible: Boolean,
    width: Dp,
    uiState: WorkspaceUiState,
    callbacks: WorkspaceCallbacks,
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
            FileTreePane(
                state = uiState,
                onFileOpened = callbacks.onFileOpened,
                onDirectoryToggled = callbacks.onDirectoryToggled,
                onNodeMenu = onNodeMenu,
                onNewFile = onNewFile,
                onNewFolder = onNewFolder,
                onRefresh = callbacks.onRefreshTree,
                modifier = Modifier.width(width).fillMaxHeight(),
            )
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
            FileTreePane(
                state = uiState,
                onFileOpened = callbacks.onFileOpened,
                onDirectoryToggled = callbacks.onDirectoryToggled,
                onNodeMenu = onNodeMenu,
                onNewFile = onNewFile,
                onNewFolder = onNewFolder,
                onRefresh = callbacks.onRefreshTree,
                modifier = Modifier.width(width).fillMaxHeight(),
            )
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
