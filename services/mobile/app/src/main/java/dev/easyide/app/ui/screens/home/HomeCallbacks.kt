package dev.easyide.app.ui.screens.home

import android.net.Uri

/** Everything Home can ask of its owner, grouped so the screen's signature stays readable. */
class HomeCallbacks(
    val onQueryChanged: (String) -> Unit,
    val onSortChanged: (ProjectSort) -> Unit,
    val onSelect: (String) -> Unit,
    val onCloseDetail: () -> Unit,
    val onResumed: () -> Unit,
    /** [withTerminal] reveals the terminal on arrival instead of leaving the workspace as the user last had it. */
    val onOpenProject: (ProjectListItem, withTerminal: Boolean) -> Unit,
    val onNewProject: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onInstallLinux: () -> Unit,
    val onDialog: (HomeDialog?) -> Unit,
    val onFolderPicked: (Uri) -> Unit,
    val onFolderPickFailed: () -> Unit,
    val onMessageShown: () -> Unit,
    val rename: (projectId: String, name: String) -> Unit,
    val duplicate: (projectId: String, name: String) -> Unit,
    val delete: (projectId: String) -> Unit,
    val changeEnvironment: (projectId: String, environmentId: String) -> Unit,
    val importFolder: (treeUri: String, name: String, environmentId: String) -> Unit,
    val clone: (CloneUrl, name: String, environmentId: String) -> Unit,
)
