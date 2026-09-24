package dev.easyide.app.ui.screens.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.easyide.app.R
import dev.easyide.app.ui.components.rememberFolderPicker
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.sandbox.external.ExternalFolderSync

/**
 * Home, the nav root. At expanded width it is a list on the left and the selected
 * project's detail on the right; anywhere narrower it is one column, and tapping
 * a project opens its detail as a screen of its own (Back returns to the list).
 * See docs/ux-overhaul/arch.md Pillar 4 "Home".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    externalFolderSync: ExternalFolderSync,
    callbacks: HomeCallbacks,
    modifier: Modifier = Modifier,
) {
    val twoPane = LocalWindowSize.current.width.isExpanded
    val context = LocalContext.current
    val now by rememberNow()
    val snackbar = remember { SnackbarHostState() }
    val pickFolder = rememberFolderPicker(externalFolderSync, callbacks.onFolderPicked, callbacks.onFolderPickFailed)

    // Git state and file times move while a workspace or the terminal is open.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { callbacks.onResumed() }

    val message = uiState.message
    val messageText = message?.text()
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            callbacks.onMessageShown()
        }
    }

    val showDetailScreen = !twoPane && uiState.detailOpen && uiState.selected != null
    BackHandler(enabled = showDetailScreen, onBack = callbacks.onCloseDetail)

    val actionsFor: (ProjectListItem) -> ProjectMenuActions = { item ->
        ProjectMenuActions(
            onOpen = { callbacks.onOpenProject(item, false) },
            onRename = { callbacks.onDialog(HomeDialog.Rename(item.project.id)) },
            onDuplicate = { callbacks.onDialog(HomeDialog.Duplicate(item.project.id)) },
            onChangeEnvironment = { callbacks.onDialog(HomeDialog.ChangeEnvironment(item.project.id)) },
            onOpenLocation = item.project.externalFolderUri?.let { uri -> { openFolderLocation(context, uri) } },
            onDelete = { callbacks.onDialog(HomeDialog.Delete(item.project.id)) },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            HomeTopBar(
                title = if (showDetailScreen) uiState.selected?.project?.name.orEmpty() else stringResource(R.string.nav_home),
                onBack = if (showDetailScreen) callbacks.onCloseDetail else null,
                onOpenSettings = callbacks.onOpenSettings,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val selected = uiState.selected
        when {
            showDetailScreen && selected != null -> ProjectDetail(
                item = selected,
                recentFiles = uiState.recentFiles,
                nowMs = now,
                onOpen = { callbacks.onOpenProject(selected, false) },
                onOpenTerminal = { callbacks.onOpenProject(selected, true) },
                actions = actionsFor(selected),
                showTitle = false,
                modifier = Modifier.padding(padding),
            )

            !uiState.isLoading && uiState.projectCount == 0 -> HomeEmpty(
                needsLinux = uiState.needsLinux,
                onInstallLinux = callbacks.onInstallLinux,
                onNewProject = callbacks.onNewProject,
                onImportFolder = pickFolder,
                onClone = { callbacks.onDialog(HomeDialog.Clone) },
                modifier = Modifier.padding(padding),
            )

            twoPane -> Row(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.weight(HomeMetrics.LIST_PANE_WEIGHT).widthIn(min = HomeMetrics.listPaneMinWidth, max = HomeMetrics.listPaneMaxWidth)) {
                    HomeList(uiState, now, PaddingValues(), actionsFor, callbacks, pickFolder)
                }
                VerticalDivider(Modifier.fillMaxHeight())
                Box(Modifier.weight(HomeMetrics.DETAIL_PANE_WEIGHT).fillMaxHeight()) {
                    if (selected != null) {
                        ProjectDetail(
                            item = selected,
                            recentFiles = uiState.recentFiles,
                            nowMs = now,
                            onOpen = { callbacks.onOpenProject(selected, false) },
                            onOpenTerminal = { callbacks.onOpenProject(selected, true) },
                            actions = actionsFor(selected),
                        )
                    } else if (!uiState.isLoading) {
                        Text(
                            text = stringResource(R.string.home_detail_none_selected),
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = HomeMetrics.singleColumnMaxWidth)) {
                    HomeList(uiState, now, padding, actionsFor, callbacks, pickFolder)
                }
            }
        }
    }

    HomeDialogHost(uiState, callbacks)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(title: String, onBack: (() -> Unit)?, onOpenSettings: () -> Unit) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_settings))
            }
        },
    )
}

/** The list pane: controls, then the skeleton or the projects (and a no-results note when a search matches none). */
@Composable
private fun HomeList(
    state: HomeUiState,
    nowMs: Long,
    insets: PaddingValues,
    actionsFor: (ProjectListItem) -> ProjectMenuActions,
    callbacks: HomeCallbacks,
    pickFolder: () -> Unit,
) {
    val padding = listPadding(insets, extraBottom = Spacing.l)
    val controls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m), modifier = Modifier.padding(bottom = Spacing.s)) {
            if (state.needsLinux) InstallLinuxCard(callbacks.onInstallLinux)
            ListControls(
                query = state.query,
                sort = state.sort,
                onQueryChanged = callbacks.onQueryChanged,
                onSortChanged = callbacks.onSortChanged,
                onNewProject = callbacks.onNewProject,
                onImportFolder = pickFolder,
                onClone = { callbacks.onDialog(HomeDialog.Clone) },
            )
        }
    }

    when {
        state.isLoading -> ProjectListSkeleton(padding, controls)

        else -> ProjectList(
            items = state.visible,
            selectedId = state.selected?.project?.id,
            nowMs = nowMs,
            contentPadding = padding,
            actionsFor = actionsFor,
            onSelect = { callbacks.onSelect(it.project.id) },
            header = controls,
            footer = if (state.visible.isEmpty()) ({ NoMatches(state.query) }) else null,
        )
    }
}

/**
 * Shows the linked folder in the system file manager. The intent may have no
 * handler on a locked-down device; that is a platform boundary, and there is
 * nothing to fall back to, so it is a no-op rather than a crash.
 */
private fun openFolderLocation(context: Context, treeUri: String) {
    val tree = Uri.parse(treeUri)
    val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(document, DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No file manager installed that can show a folder.
    }
}
