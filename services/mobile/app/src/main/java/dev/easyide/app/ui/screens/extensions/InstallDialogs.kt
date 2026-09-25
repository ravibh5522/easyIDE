package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitChoice
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitProgress
import dev.easyide.app.ui.kit.Tone
import dev.easyide.extensions.contrib.Contributions
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.sandbox.model.SandboxEnvironment

/**
 * The capability sheet of an install (ECO-03): source label "unsigned, local" or "registry
 * <id>, signed by <keyId>" (registry-and-install.md sec 8.2 step 5), every declared
 * capability with its sdk-reference prompt text, the setup commands verbatim, the scope, and
 * for environment packs the target environment. Declining installs nothing.
 */
@Composable
internal fun InstallDialogs(install: InstallState, environments: List<SandboxEnvironment>, viewModel: ExtensionsViewModel) {
    when (install) {
        InstallState.Idle -> Unit
        InstallState.Staging -> KitDialog(stringResource(R.string.ext_install_checking), onDismiss = {}) { KitProgress(fraction = null) }
        is InstallState.Refused -> ProblemDialog(stringResource(R.string.ext_install_refused), install.problems, viewModel::dismissInstall)
        is InstallState.Failed -> ProblemDialog(stringResource(R.string.ext_install_failed), listOf(install.message), viewModel::dismissInstall)
        is InstallState.Review -> ReviewDialog(install, environments, viewModel)
    }
}

@Composable
private fun ReviewDialog(install: InstallState.Review, environments: List<SandboxEnvironment>, viewModel: ExtensionsViewModel) {
    val d = install.pkg.descriptor
    val needsEnv = d.scope == InstallScope.ENVIRONMENT
    var envId by remember(install) { mutableStateOf(install.dev?.envId ?: environments.firstOrNull()?.id) }
    val canInstall = !install.pkg.alreadyInstalled && (!needsEnv || envId != null)
    KitDialog(
        title = stringResource(R.string.ext_install_review_title, d.displayName, d.version.toString()),
        onDismiss = { viewModel.decline(install.pkg) },
        confirm = KitAction(stringResource(R.string.ext_install_approve)) { viewModel.approve(install, envId) }.takeIf { canInstall },
        dismiss = KitAction(stringResource(R.string.ext_prompt_cancel)) { viewModel.decline(install.pkg) },
    ) {
        val signed = install.registry
        if (signed == null) {
            KitBanner(stringResource(R.string.ext_install_unsigned), Modifier.padding(bottom = Kit.space.s), Tone.Warning)
        } else {
            KitBanner(stringResource(R.string.ext_install_signed, signed.registryId, signed.signedBy), Modifier.padding(bottom = Kit.space.s), Tone.Info)
            if (signed.fromCache) DialogText(stringResource(R.string.ext_install_from_cache), muted = true)
        }
        install.dev?.let { DialogText(devReason(it)) }
        DialogMono(d.id.value)
        d.description?.let { DialogText(it, muted = true) }
        if (install.pkg.alreadyInstalled) KitBanner(stringResource(R.string.ext_install_already), Modifier.padding(top = Kit.space.s), Tone.Danger)
        DialogHeading(stringResource(R.string.ext_capabilities))
        if (d.capabilities.items.isEmpty()) DialogText(stringResource(R.string.ext_capabilities_none), muted = true)
        d.capabilities.items.sortedBy { it.id }.forEach { DialogText(capabilityPrompt(it, envId)) }
        InstallCommands(d.contributes)
        DialogText(stringResource(R.string.ext_install_not_isolated), muted = true, modifier = Modifier.padding(top = Kit.space.s))
        if (needsEnv) EnvironmentChoice(environments, envId) { envId = it }
    }
}

@Composable
private fun EnvironmentChoice(environments: List<SandboxEnvironment>, envId: String?, onPick: (String) -> Unit) {
    DialogHeading(stringResource(R.string.ext_install_environment))
    if (environments.isEmpty()) {
        KitBanner(stringResource(R.string.ext_install_no_environment), tone = Tone.Danger)
        return
    }
    KitChoice(environments, environments.firstOrNull { it.id == envId } ?: environments.first(), { it.label }, { onPick(it.id) })
}

/**
 * Environment setup steps and language server commands, verbatim (registry-and-install.md
 * sec 8.2 step 5): what the pack will run is shown before it is approved, not after.
 */
@Composable
private fun InstallCommands(c: Contributions) {
    val steps = c.sandbox?.install.orEmpty()
    if (steps.isNotEmpty()) {
        DialogHeading(stringResource(R.string.ext_install_setup_steps))
        DialogText(stringResource(R.string.ext_install_setup_when), muted = true)
        steps.forEach { step ->
            DialogText(step.title)
            DialogMono(step.run.source)
        }
    }
    if (c.languageServers.isNotEmpty()) {
        DialogHeading(stringResource(R.string.ext_install_servers))
        c.languageServers.forEach { s ->
            val budget = s.memoryBudgetMb?.let { " (${stringResource(R.string.ext_install_server_budget, it)})" }.orEmpty()
            DialogMono(s.command.joinToString(" ") { it.source } + budget)
        }
    }
}
