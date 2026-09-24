package dev.easyide.app.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.components.label
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag

/**
 * Each Linux environment with its backend, how many projects use it and its state. A row opens the
 * Sandbox settings document, where environments are managed. Without one, the only next step is
 * installing Linux. Size on disk is left out until the runtime can report it.
 */
@Composable
internal fun EnvironmentSection(state: HomeUiState, onOpenSettings: () -> Unit, onInstallLinux: () -> Unit) {
    if (state.isLoading) return
    KitSection(stringResource(R.string.home_section_environment)) {
        if (state.environments.isEmpty()) {
            KitEmptyState(
                art = EmptyArt.Prompt,
                message = stringResource(R.string.home_env_empty),
                action = KitAction(stringResource(R.string.home_env_install), onInstallLinux),
            )
        }
        state.environments.forEach { env ->
            val count = state.projectsIn(env.id)
            KitRow(
                title = env.label,
                subtitle = joinParts(env.backend.label(), pluralStringResource(R.plurals.home_env_projects, count, count)),
                onClick = onOpenSettings,
                trailing = { KitTag(env.state.tagText(), tone = env.state.tone()) },
            )
        }
    }
}
