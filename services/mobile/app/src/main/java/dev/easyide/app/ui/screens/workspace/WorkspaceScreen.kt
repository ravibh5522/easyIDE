package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.view.TerminalView
import dev.easyide.app.R
import dev.easyide.app.extensions.ExtensionsContainer
import dev.easyide.app.extensions.adapters.KeySurface
import dev.easyide.app.ui.commands.ChordDispatcher
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.app.ui.commands.CommandPalette
import dev.easyide.app.ui.commands.KeyChord
import dev.easyide.app.ui.foundation.LocalKeymap
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.screens.workspace.decor.DecorationRegistry
import dev.easyide.app.ui.screens.workspace.ext.ContributedMenu
import dev.easyide.app.ui.screens.workspace.ext.EditorTitleActions
import dev.easyide.app.ui.screens.workspace.ext.ExtensionEffects
import dev.easyide.app.ui.screens.workspace.ext.PickerLabels
import dev.easyide.app.ui.screens.workspace.ext.StatusItemsRow
import dev.easyide.app.ui.screens.workspace.ext.WorkspaceExtensionHost
import dev.easyide.app.ui.screens.workspace.ext.rememberHardwareKeyboard
import dev.easyide.app.ui.screens.workspace.ext.rememberInputMode
import dev.easyide.app.ui.screens.workspace.ext.rememberWorkspaceContributions
import dev.easyide.app.ui.screens.workspace.ext.trackInputMode
import dev.easyide.app.ui.screens.workspace.lsp.LspDialogs
import dev.easyide.app.ui.screens.workspace.lsp.LspInstallNotice
import dev.easyide.app.ui.screens.workspace.lsp.LspStatusItems
import dev.easyide.app.ui.screens.workspace.lsp.SymbolScope
import dev.easyide.app.ui.screens.workspace.lsp.WorkspaceLspController
import dev.easyide.app.ui.screens.workspace.lsp.lspCommands
import dev.easyide.app.ui.screens.workspace.session.ExternalChangeDialog
import dev.easyide.app.ui.screens.workspace.session.WorkspaceSessionUi
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.host.AppRenderers
import dev.easyide.app.ui.shell.host.ShellDeps
import dev.easyide.app.ui.shell.host.ShellTokens
import dev.easyide.app.ui.shell.host.ShellViewModel
import dev.easyide.app.ui.shell.host.panelRenderer
import dev.easyide.app.ui.shell.workspace.DockFocus
import dev.easyide.app.ui.shell.workspace.DockRules
import dev.easyide.app.ui.shell.workspace.FileDocuments
import dev.easyide.app.ui.shell.workspace.InputDock
import dev.easyide.app.ui.shell.workspace.LayoutPresetPicker
import dev.easyide.app.ui.shell.workspace.LocalWorkspaceEnv
import dev.easyide.app.ui.shell.workspace.StatusParts
import dev.easyide.app.ui.shell.workspace.StatusStrip
import dev.easyide.app.ui.shell.workspace.ShellStageAccess
import dev.easyide.app.ui.shell.workspace.WorkspaceActions
import dev.easyide.app.ui.shell.workspace.WorkspaceChrome
import dev.easyide.app.ui.shell.workspace.WorkspaceEffects
import dev.easyide.app.ui.shell.workspace.WorkspaceEnv
import dev.easyide.app.ui.shell.workspace.WorkspaceNavState
import dev.easyide.app.ui.shell.workspace.WorkspacePanels
import dev.easyide.app.ui.shell.workspace.WorkspaceParts
import dev.easyide.app.ui.shell.workspace.WorkspaceShell
import dev.easyide.app.ui.shell.workspace.WorkspaceShellHost
import dev.easyide.app.ui.shell.workspace.WorkspaceSlots
import dev.easyide.app.ui.shell.workspace.terminalRowKey
import dev.easyide.app.ui.shell.workspace.workspaceShellCommands
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.extensions.contrib.MenuIds
import dev.easyide.extensions.contrib.StatusBarAlignment
import dev.easyide.sandbox.files.FileNode
import kotlinx.coroutines.launch

/**
 * The IDE screen. The layout, navigation and documents are the workspace shell's
 * ([WorkspaceShell]); what stays here is what needs the workspace's whole state at once: the
 * commands and key dispatch, the dialogs and overlays that sit over everything, and the guard on
 * closing anything with unsaved edits.
 */
@Composable
fun WorkspaceScreen(
    projectName: String,
    uiState: WorkspaceUiState,
    callbacks: WorkspaceCallbacks,
    gitState: GitPanelState,
    gitCallbacks: SourceControlCallbacks,
    decorations: DecorationRegistry,
    lsp: WorkspaceLspController,
    extensionHost: WorkspaceExtensionHost,
    extensions: ExtensionsContainer,
    selections: EditorSelections,
    session: WorkspaceSessionUi,
    editing: WorkspaceEditing,
    shell: ShellViewModel,
    deps: ShellDeps,
    /** Ends the project (shells and buffers), as opposed to [WorkspaceCallbacks.onBack], which only leaves it running. */
    onCloseProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowSize = LocalWindowSize.current
    val model = session.shell
    val shellState by model.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var menuNode by remember { mutableStateOf<FileNode?>(null) }
    var prompt by remember { mutableStateOf<PendingPrompt?>(null) }
    var paletteOpen by rememberSaveable { mutableStateOf(false) }
    // What Go to File's `>` prefix hands over to the palette.
    var paletteQuery by remember { mutableStateOf("") }
    var presetsOpen by remember { mutableStateOf(false) }
    var editorFocus by remember { mutableStateOf(false) }
    var terminalFocus by remember { mutableStateOf(false) }
    val overlays = remember { EditingOverlays() }
    val recentFiles by editing.files.recent.collectAsState()

    LaunchedEffect(uiState.statusMessage) {
        val message = uiState.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        callbacks.onStatusShown()
    }

    // Leaving (system back, the rail's Back item) parks the workspace: shells keep running and buffers stay in
    // memory, so there is nothing to lose and nothing to ask. Closing it is the explicit act that drops them,
    // and that one asks about unsaved edits, as does closing a dirty tab.
    val dirtyTabs = uiState.openTabs.filter { it.isDirty }
    val requestClose: () -> Unit = {
        if (dirtyTabs.isEmpty()) onCloseProject() else prompt = PendingPrompt.CloseWithUnsaved(dirtyTabs)
    }
    val requestCloseTab: (String) -> Unit = { path ->
        val tab = uiState.openTabs.find { it.relativePath == path }
        if (tab?.isDirty == true) prompt = PendingPrompt.CloseDirtyTab(tab) else callbacks.onTabClosed(path)
    }

    val contributions = rememberWorkspaceContributions(extensionHost, extensions)
    val snippetLabels = PickerLabels(
        stringResource(R.string.command_insert_snippet), stringResource(R.string.ext_snippet_pick_hint), stringResource(R.string.ext_snippet_none),
    )
    val taskLabels = PickerLabels(stringResource(R.string.command_run_task), stringResource(R.string.ext_task_pick_hint), stringResource(R.string.ext_task_none))
    val taskExited = stringResource(R.string.ext_task_exited)
    val commands = workspaceCommands(
        uiState = uiState,
        callbacks = callbacks,
        shell = WorkspaceShellActions(
            toggleExplorer = { model.toggleContainer(CoreShell.EXPLORER) },
            toggleSourceControl = { model.toggleContainer(CoreShell.SOURCE_CONTROL) },
            toggleTerminal = { model.toggleContainer(CoreShell.TERMINAL) },
            showCommands = { paletteOpen = true },
            closeTab = requestCloseTab,
            insertSnippet = { extensionHost.pickSnippet(snippetLabels) },
            toggleLineComment = extensionHost.lineComments::toggle,
            runTask = { extensionHost.pickTask(taskLabels) { label, code -> taskExited.format(label, code) } },
            showExtensions = { model.toggleContainer(CoreShell.EXTENSIONS_LIST) },
        ),
        extra = lspCommands(lsp) + editingCommands(uiState, editing, overlays) +
            workspaceShellCommands(shellState, model, callbacks.onBack, requestClose) { presetsOpen = true },
    ) + contributions.commands()
    // Built-in < extension layer < keybindings.json, resolved once in AppContainer.
    val keymap = LocalKeymap.current
    // Per screen: the pending half of a two-step chord (Ctrl+K ...) is UI state.
    val dispatcher = remember(keymap) { ChordDispatcher(keymap) }
    val keyContext = contributions.context
    val themeSemantic = editorColors.semantic.highlighting ?: false
    LaunchedEffect(lsp, themeSemantic) { lsp.onThemeSemanticHighlighting(themeSemantic) }
    SideEffect {
        extensionHost.commands = commands
        extensionHost.projectName = projectName
    }
    val inputMode = rememberInputMode()
    val hardwareKeyboard = rememberHardwareKeyboard()
    val runShortcut: (KeyChord) -> Unit = { chord ->
        keymap.bindingFor(chord, terminalFocused = false, context = keyContext)?.let { commands.execute(it.command, it.args) }
    }
    val hostView = LocalView.current
    val rootFocus = remember { FocusRequester() }

    // Shortcuts need a focused node to be delivered at all, so the root takes
    // focus when nothing else holds it - but never from the terminal, which
    // grabs focus on attach so typing on a hardware keyboard goes to the shell.
    LaunchedEffect(Unit) {
        if (hostView.rootView.findFocus() == null) rootFocus.requestFocus()
    }

    val env = WorkspaceEnv(
        projectName, uiState, callbacks, gitState, gitCallbacks, lsp, decorations, selections, session, editing, contributions, commands, extensionHost,
        WorkspaceActions(
            onNodeMenu = { menuNode = it },
            onNewFile = { prompt = PendingPrompt.NewFile("") },
            onNewFolder = { prompt = PendingPrompt.NewFolder("") },
            onRenameTerminal = { id, title -> prompt = PendingPrompt.RenameTerminal(id, title) },
            closeFile = requestCloseTab,
            onTerminalKey = { e -> dispatcher.dispatch(e, terminalFocused = true, commands, keyContext) },
            onTerminalRowKey = { action, terminal -> terminalRowKey(action, terminal, extensionHost::run) },
            onEditorRowKey = { extensionHost.editorKey(it, runShortcut) },
            onEditorFocus = { editorFocus = it },
            onTerminalFocus = { terminalFocus = it },
            onFindFieldFocus = { overlays.findFieldFocused = it },
        ),
    )

    // What the active group shows, when it is a file: the status strip, the title actions and the dock speak for it.
    val activeFile = FileDocuments.pathOf(shellState?.current?.stage?.activeGroup?.active)?.let { path -> uiState.openTabs.find { it.relativePath == path } }
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val compact = windowSize.width.isCompact
    val dockFocus = when {
        terminalFocus -> DockFocus.TERMINAL
        editorFocus -> DockFocus.EDITOR
        else -> DockFocus.NONE
    }
    val dockShown = DockRules.visible(dockFocus, keyboardUp, hardwareKeyboard, compact)

    val transientOpen = paletteOpen || overlays.quickOpen || overlays.goToLine || editing.find.isOpen || presetsOpen
    val navItems by shell.workspaceNavItems.collectAsStateWithLifecycle()
    val navSettings by shell.navSettings.collectAsStateWithLifecycle()
    val navBadges by shell.navBadges.collectAsStateWithLifecycle()
    val parts = remember(model, deps) {
        WorkspaceParts(model.documents, model.containers, AppRenderers.panels(deps) + WorkspacePanels.panels(), AppRenderers.documents(deps) + WorkspacePanels.documents())
    }
    val slots = WorkspaceSlots(
        idle = panelRenderer { m ->
            WelcomeView(
                recent = recentFiles.paths,
                registry = commands,
                keymap = keymap,
                onOpenFile = { path -> callbacks.onFileOpened(FileNode(path.substringAfterLast('/'), path, isDirectory = false, sizeBytes = 0)) },
                modifier = m,
            )
        },
        emptyTitle = projectName,
        trailing = { EditorActions(activeFile, callbacks, env) },
        notice = { LspInstallNotice(lsp) },
        onTerminalFocus = { terminalFocus = it },
    )
    val host = WorkspaceShellHost(
        runCommand = { commands.execute(it) },
        notify = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
        transientOpen = transientOpen,
        dismissTransient = {
            paletteOpen = false
            paletteQuery = ""
            overlays.quickOpen = false
            overlays.goToLine = false
            presetsOpen = false
            editing.find.close()
        },
        leave = callbacks.onBack,
        activateFile = callbacks.onTabSelected,
        closeFile = requestCloseTab,
        isFileDirty = { path -> uiState.openTabs.find { it.relativePath == path }?.isDirty == true },
    )
    val chrome = WorkspaceChrome(
        status = {
            StatusStrip(
                StatusParts(
                    project = projectName,
                    file = activeFile?.name,
                    lines = activeFile?.let { LineCount.of(it.content) },
                    dirty = activeFile?.isDirty == true,
                    onSave = callbacks.onSave,
                    extLeft = { StatusItemsRow(contributions.statusItems(), StatusBarAlignment.LEFT) { extensionHost.run(it) } },
                    extRight = { StatusItemsRow(contributions.statusItems(), StatusBarAlignment.RIGHT) { extensionHost.run(it) } },
                    problems = { LspStatusItems(lsp) },
                ),
                windowSize.width,
            )
        },
        dock = if (dockShown) ({ InputDock(env, dockFocus, activeFile?.editable == true, inputMode, runShortcut) }) else null,
        statusHidden = compact && keyboardUp,
    )

    Box(
        modifier = modifier
            .background(editorColors.background)
            .onPreviewKeyEvent { event ->
                // A focused terminal dispatches its own keys (see
                // EasyTerminalViewClient.onHardwareKey); handling them here as
                // well would run a command twice or steal a shell chord.
                inputMode.onKey()
                if (hostView.rootView.findFocus() is TerminalView) return@onPreviewKeyEvent false
                // A find, palette or go-to-line field keeps Ctrl+Z / Ctrl+Y for its own text.
                if ((overlays.ownsTextInput || paletteOpen) &&
                    keymap.commandFor(KeyChord.of(event.nativeKeyEvent), terminalFocused = false, context = keyContext) in CommandIds.HISTORY
                ) return@onPreviewKeyEvent false
                dispatcher.dispatch(event.nativeKeyEvent, terminalFocused = false, commands, keyContext)
            }
            .trackInputMode(inputMode)
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        CompositionLocalProvider(LocalWorkspaceEnv provides env) {
            WorkspaceShell(model, parts, WorkspaceNavState(navItems, navSettings, navBadges), slots, host, chrome)
            WorkspaceEffects(model, env)
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
            extensionEntries = contributions::explorerMenu,
            onExtensionEntry = { entry, node -> menuNode = null; contributions.runOnNode(entry, node) },
        )

        ExtensionEffects(extensionHost, extensions, ShellStageAccess(shellState, model), hardwareKeyboard, windowSize, inputMode, terminalFocus, editorFocus, snackbarHostState)

        PromptDialogs(prompt, callbacks, onCloseProject) { prompt = null }
        ExternalChangeDialog(uiState.openTabs, session.onResolveConflict)
        LspDialogs(lsp)
        AppRenderers.ExtensionsDialogs(deps)

        // Shared by the palette and Go to File: `@` and `#` open the language servers' symbol pickers.
        val openSymbolPicker: (Char, String) -> Boolean = { prefix, query ->
            val scope = when (prefix) {
                SYMBOL_PREFIX_DOCUMENT -> SymbolScope.DOCUMENT
                SYMBOL_PREFIX_WORKSPACE -> SymbolScope.WORKSPACE
                else -> null
            }
            scope?.let { lsp.navigation.openPicker(it, query) } != null
        }
        if (paletteOpen) {
            CommandPalette(
                registry = commands,
                keymap = keymap,
                onDismiss = { paletteOpen = false; paletteQuery = "" },
                initialQuery = paletteQuery,
                onPrefix = openSymbolPicker,
            )
        }
        EditingOverlayHost(
            overlays = overlays,
            editing = editing,
            uiState = uiState,
            onOpenFile = callbacks.onFileOpened,
            onCommands = { query -> paletteQuery = query; paletteOpen = true },
            onPrefix = openSymbolPicker,
        )
        if (presetsOpen) {
            shellState?.let { LayoutPresetPicker(it, { id -> model.dispatch(dev.easyide.app.ui.shell.ShellAction.ApplyPreset(id)) }, { presetsOpen = false }) }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (compact) Kit.control.bottomBarHeight + Kit.space.m else Kit.space.xxxl),
        )
    }
}

/** The active group's own actions: the markdown preview toggle and the contributed `editor/title` entries, for a file. */
@Composable
private fun RowScope.EditorActions(file: EditorTab?, callbacks: WorkspaceCallbacks, env: WorkspaceEnv) {
    if (file == null) return
    val contributions = env.contributions
    if (file.isMarkdown) {
        KitIconButton(
            if (file.showPreview) Icons.Filled.Code else Icons.Filled.Visibility,
            stringResource(if (file.showPreview) R.string.wshell_preview_source else R.string.wshell_preview_show),
            callbacks.onTogglePreview,
        )
    }
    EditorTitleActions(
        title = contributions.menu(MenuIds.EDITOR_TITLE, env.commands),
        context = contributions.menu(MenuIds.EDITOR_CONTEXT, env.commands),
        onRun = { contributions.run(it) },
    )
}

/** Command-palette quick-open prefixes (VS Code's): document symbols, workspace symbols. */
private const val SYMBOL_PREFIX_DOCUMENT = '@'
private const val SYMBOL_PREFIX_WORKSPACE = '#'
