package dev.easyide.app.ui.screens.extensions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Every dialog the extensions surfaces can raise: the install approval with its capability list,
 * a Browse row's detail, rollback, and Create extension. Their state lives in the view model, so
 * the shell mounts this once next to the panel and pages; a second copy would show each twice.
 */
@Composable
fun ExtensionsDialogs(viewModel: ExtensionsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val install by viewModel.install.collectAsStateWithLifecycle()
    val rollback by viewModel.rollback.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val create by viewModel.create.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val developerMode by viewModel.developerMode.collectAsStateWithLifecycle()

    detail?.let { RegistryDetail(it, viewModel::installFromRegistry, viewModel::forgetPin, viewModel::closeDetail) }
    InstallDialogs(install, state.environments, viewModel)
    CreateExtensionDialogs(create, projects, developerMode, viewModel)
    RollbackDialogs(rollback, viewModel)
}
