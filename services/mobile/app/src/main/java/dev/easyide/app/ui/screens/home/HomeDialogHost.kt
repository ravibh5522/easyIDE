package dev.easyide.app.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.sandbox.ProjectNames

/**
 * Renders whichever [HomeDialog] the ViewModel says is open. Compose it once wherever Home content
 * can be on screen; the panel, the project page and a row's menu all ask their question here. A
 * dialog naming a project that no longer exists (deleted from another path) renders nothing and
 * is dismissed by the ViewModel's own state, never left dangling. Cloning and importing need an
 * installed Linux environment, so with none they become the Install Linux prompt.
 */
@Composable
fun HomeDialogHost(state: HomeUiState, callbacks: HomeCallbacks) {
    val dialog = state.dialog ?: return
    val busy = state.busy != null
    val dismiss = { callbacks.onDialog(null) }
    val allItems = state.projectNames
    val installPrompt: @Composable () -> Unit = {
        InstallLinuxDialog(onInstall = { dismiss(); callbacks.onInstallLinux() }, onDismiss = dismiss)
    }

    fun others(exceptId: String?) = allItems.filterKeys { it != exceptId }.values

    when (dialog) {
        is HomeDialog.Rename -> {
            val name = allItems[dialog.projectId] ?: return
            NameDialog(
                title = R.string.home_rename_title,
                confirmLabel = R.string.home_rename_confirm,
                initial = name,
                otherNames = others(dialog.projectId),
                allowUnchanged = false,
                busy = busy,
                failure = state.dialogFailure,
                onConfirm = { callbacks.rename(dialog.projectId, it) },
                onDismiss = dismiss,
            )
        }

        is HomeDialog.Duplicate -> {
            val name = allItems[dialog.projectId] ?: return
            val proposal = ProjectNames.unique(stringResource(R.string.home_duplicate_default_name, name), allItems.values)
            NameDialog(
                title = R.string.home_duplicate_title,
                confirmLabel = R.string.home_duplicate_confirm,
                initial = proposal,
                otherNames = allItems.values,
                allowUnchanged = true,
                busy = busy,
                failure = state.dialogFailure,
                onConfirm = { callbacks.duplicate(dialog.projectId, it) },
                onDismiss = dismiss,
            )
        }

        is HomeDialog.Delete -> {
            val item = state.find(dialog.projectId) ?: return
            DeleteProjectDialog(item, busy, state.dialogFailure, { callbacks.delete(dialog.projectId) }, dismiss)
        }

        is HomeDialog.ChangeEnvironment -> {
            val item = state.find(dialog.projectId) ?: return
            ChangeEnvironmentDialog(
                item = item,
                environments = state.environments,
                busy = busy,
                failure = state.dialogFailure,
                onConfirm = { callbacks.changeEnvironment(dialog.projectId, it) },
                onDismiss = dismiss,
            )
        }

        HomeDialog.Clone -> if (state.readyEnvironments.isEmpty()) installPrompt() else CloneDialog(
            environments = state.readyEnvironments,
            suggestedEnvironmentId = state.suggestedEnvironmentId,
            otherNames = allItems.values,
            busy = busy,
            failure = state.dialogFailure,
            onConfirm = callbacks.clone,
            onDismiss = dismiss,
        )

        is HomeDialog.Import -> if (state.environments.isEmpty()) installPrompt() else ImportDialog(
            suggestedName = dialog.suggestedName,
            environments = state.environments,
            suggestedEnvironmentId = state.suggestedEnvironmentId,
            otherNames = allItems.values,
            busy = busy,
            failure = state.dialogFailure,
            onConfirm = { name, environment -> callbacks.importFolder(dialog.treeUri, name, environment) },
            onDismiss = dismiss,
        )
    }
}
