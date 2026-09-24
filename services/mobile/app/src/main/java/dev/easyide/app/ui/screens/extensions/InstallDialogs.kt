package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.extensions.capability.Capability
import dev.easyide.extensions.contrib.Contributions
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.sandbox.model.SandboxEnvironment

/**
 * The capability sheet of an install (ECO-03): source label "unsigned, local" or "registry
 * <id>, signed by <keyId>" (registry-and-install.md sec 8.2 step 5), every
 * declared capability with its sdk-reference prompt text, the scope, and for environment
 * packs the target environment. Declining installs nothing.
 */
@Composable
fun InstallDialogs(install: InstallState, environments: List<SandboxEnvironment>, viewModel: ExtensionsViewModel) {
    when (install) {
        InstallState.Idle -> Unit
        InstallState.Staging -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.ext_install_checking)) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
        is InstallState.Refused -> ProblemDialog(stringResource(R.string.ext_install_refused), install.problems, viewModel::dismissInstall)
        is InstallState.Failed -> ProblemDialog(stringResource(R.string.ext_install_failed), listOf(install.message), viewModel::dismissInstall)
        is InstallState.Review -> {
            val d = install.pkg.descriptor
            val needsEnv = d.scope == InstallScope.ENVIRONMENT
            var envId by remember(install) { mutableStateOf(environments.firstOrNull()?.id) }
            AlertDialog(
                onDismissRequest = { viewModel.decline(install.pkg) },
                title = { Text(stringResource(R.string.ext_install_review_title, d.displayName, d.version.toString())) },
                text = {
                    Column(modifier = Modifier.heightIn(max = SHEET_MAX_DP.dp).verticalScroll(rememberScrollState())) {
                        val signed = install.registry
                        if (signed == null) {
                            Text(stringResource(R.string.ext_install_unsigned), color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(stringResource(R.string.ext_install_signed, signed.registryId, signed.signedBy))
                            if (signed.fromCache) Text(stringResource(R.string.ext_install_from_cache), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(d.id.value, style = MaterialTheme.typography.bodySmall)
                        d.description?.let { Text(it) }
                        if (install.pkg.alreadyInstalled) Text(stringResource(R.string.ext_install_already), color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.ext_capabilities), style = MaterialTheme.typography.titleSmall)
                        if (d.capabilities.items.isEmpty()) Text(stringResource(R.string.ext_capabilities_none))
                        d.capabilities.items.sortedBy { it.id }.forEach { Text("- ${capabilityPrompt(it, envId)}") }
                        InstallCommands(d.contributes)
                        Text(stringResource(R.string.ext_install_not_isolated), style = MaterialTheme.typography.bodySmall)
                        if (needsEnv) {
                            Text(stringResource(R.string.ext_install_environment), style = MaterialTheme.typography.titleSmall)
                            if (environments.isEmpty()) Text(stringResource(R.string.ext_install_no_environment), color = MaterialTheme.colorScheme.error)
                            environments.forEach { env ->
                                FilterChip(selected = env.id == envId, onClick = { envId = env.id }, label = { Text(env.label) })
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.approve(install, envId) },
                        enabled = !install.pkg.alreadyInstalled && (!needsEnv || envId != null),
                    ) { Text(stringResource(R.string.ext_install_approve)) }
                },
                dismissButton = { TextButton(onClick = { viewModel.decline(install.pkg) }) { Text(stringResource(R.string.ext_prompt_cancel)) } },
            )
        }
    }
}

@Composable
internal fun ProblemDialog(title: String, problems: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.heightIn(max = SHEET_MAX_DP.dp).verticalScroll(rememberScrollState())) {
                problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ext_prompt_close)) } },
    )
}

/**
 * Environment setup steps and language server commands, verbatim (registry-and-install.md
 * sec 8.2 step 5): what the pack will run is shown before it is approved, not after.
 */
@Composable
private fun InstallCommands(c: Contributions) {
    val steps = c.sandbox?.install.orEmpty()
    if (steps.isNotEmpty()) {
        Text(stringResource(R.string.ext_install_setup_steps), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.ext_install_setup_when), style = MaterialTheme.typography.bodySmall)
        steps.forEach { step ->
            Text(step.title, style = MaterialTheme.typography.bodyMedium)
            Text(step.run.source, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
    if (c.languageServers.isNotEmpty()) {
        Text(stringResource(R.string.ext_install_servers), style = MaterialTheme.typography.titleSmall)
        c.languageServers.forEach { s ->
            val budget = s.memoryBudgetMb?.let { " (${stringResource(R.string.ext_install_server_budget, it)})" }.orEmpty()
            Text(s.command.joinToString(" ") { it.source } + budget, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** The sdk-reference "Prompt text" of one capability. */
@Composable
fun capabilityPrompt(c: Capability, envId: String?): String {
    val env = envId ?: stringResource(R.string.ext_env_this)
    return when (c) {
        Capability.SandboxExec -> stringResource(R.string.cap_sandbox_exec, env)
        Capability.SandboxInstall -> stringResource(R.string.cap_sandbox_install, env)
        is Capability.Network -> stringResource(R.string.cap_network, c.hosts.sorted().joinToString())
        is Capability.FsProject -> stringResource(if (c.write) R.string.cap_fs_project_write else R.string.cap_fs_project_read)
        Capability.FsOutsideProject -> stringResource(R.string.cap_fs_outside)
        Capability.LspSpawn -> stringResource(R.string.cap_lsp_spawn)
        Capability.LspRequest -> stringResource(R.string.cap_lsp_request)
        Capability.Clipboard -> stringResource(R.string.cap_clipboard)
        Capability.UiStage -> stringResource(R.string.cap_ui_stage)
        Capability.UiSettings -> stringResource(R.string.cap_ui_settings)
        Capability.SecretsRead -> stringResource(R.string.cap_secrets_read)
    }
}

internal const val SHEET_MAX_DP = 420
