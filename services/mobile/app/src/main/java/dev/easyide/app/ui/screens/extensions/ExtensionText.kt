package dev.easyide.app.ui.screens.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.extensions.dev.DevPending
import dev.easyide.app.extensions.dev.PromptReason
import dev.easyide.app.extensions.registry.RegistryError
import dev.easyide.extensions.capability.Capability
import dev.easyide.extensions.host.ActivationState
import dev.easyide.extensions.host.DisabledReason
import dev.easyide.extensions.manifest.Source

/** The short mono label of a source tag; the long trust sentence is [trustText]. */
@Composable
internal fun sourceTag(source: Source): String = stringResource(when (source) {
    Source.BUILT_IN -> R.string.extui_source_builtin
    Source.SIDELOAD -> R.string.extui_source_local
    Source.DEV -> R.string.extui_source_dev
    Source.REGISTRY -> R.string.extui_source_registry
    Source.OPEN_VSX -> R.string.extui_source_open_vsx
})

/** What the source means for trust, in one sentence, without ever claiming isolation. */
@Composable
internal fun trustText(source: Source): String = stringResource(when (source) {
    Source.BUILT_IN -> R.string.extui_trust_builtin
    Source.SIDELOAD -> R.string.extui_trust_local
    Source.DEV -> R.string.extui_trust_dev
    Source.REGISTRY -> R.string.extui_trust_registry
    Source.OPEN_VSX -> R.string.ext_source_open_vsx
})

@Composable
internal fun tagText(tag: RowTag): String = stringResource(when (tag) {
    RowTag.Invalid -> R.string.extui_tag_invalid
    RowTag.Revoked -> R.string.extui_tag_revoked
    RowTag.Crashed -> R.string.extui_tag_crashed
    RowTag.Failed -> R.string.extui_tag_failed
    RowTag.NeedsApproval -> R.string.extui_tag_needs_approval
    RowTag.Disabled -> R.string.extui_tag_disabled
    RowTag.Update -> R.string.extui_tag_update
})

/** The full sentence for a row's state, for the page header. */
@Composable
internal fun stateLabel(row: ExtensionRow): String {
    if (row.problem != null) return stringResource(R.string.ext_state_invalid)
    val reason = row.disabledReason
    if (reason != null) return stringResource(when (reason) {
        DisabledReason.OTHER_ENVIRONMENT -> R.string.ext_state_other_env
        DisabledReason.EXTENSIONS_OFF -> R.string.ext_state_extensions_off
        DisabledReason.SAFE_MODE -> R.string.ext_state_safe_mode
        DisabledReason.USER_DISABLED -> R.string.ext_state_disabled
        DisabledReason.NOT_IN_PROFILE -> R.string.ext_state_not_in_profile
        DisabledReason.NEEDS_APPROVAL -> R.string.ext_state_needs_approval
        DisabledReason.CRASH_DISABLED -> R.string.ext_state_crash_disabled
        DisabledReason.REVOKED -> R.string.ext_state_revoked
    })
    return stringResource(when (row.activation) {
        ActivationState.ACTIVE -> R.string.ext_state_active
        ActivationState.INACTIVE -> R.string.ext_state_inactive
        ActivationState.ACTIVATING -> R.string.ext_state_activating
        ActivationState.FAILED -> R.string.ext_state_failed
        ActivationState.CRASHED -> R.string.ext_state_crashed
        ActivationState.CRASH_DISABLED -> R.string.ext_state_crash_disabled
        ActivationState.DEACTIVATING, ActivationState.DISABLED, null -> R.string.ext_state_enabled
    })
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

/** The prompt of a raw capability id; an id this build does not know is shown as written. */
@Composable
internal fun capabilityPrompt(id: String, envId: String?): String =
    Capability.parse(id)?.let { capabilityPrompt(it, envId) } ?: id

/** Why a developer install (`easyide-ext dev`) stopped at the sheet instead of reloading. */
@Composable
internal fun devReason(p: DevPending): String = when (p.reason) {
    PromptReason.FIRST_INSTALL -> stringResource(R.string.ext_dev_install_first)
    PromptReason.REPLACES_NON_DEV -> stringResource(R.string.ext_dev_install_replaces)
    PromptReason.CAPABILITIES_CHANGED -> stringResource(R.string.ext_dev_install_changed, p.added.sorted().joinToString().ifEmpty { "-" })
    PromptReason.NEEDS_ENVIRONMENT -> stringResource(R.string.ext_dev_install_env)
}

/** How old a registry index is, as the Browse tab heads it. */
@Composable
internal fun ageText(minutes: Long): String = when {
    minutes < 1 -> stringResource(R.string.reg_age_now)
    minutes < MINUTES_PER_HOUR -> stringResource(R.string.reg_age_minutes, minutes.toInt())
    minutes < MINUTES_PER_DAY -> stringResource(R.string.reg_age_hours, (minutes / MINUTES_PER_HOUR).toInt())
    else -> stringResource(R.string.reg_age_days, (minutes / MINUTES_PER_DAY).toInt())
}

@Composable
internal fun registryErrorLine(e: RegistryError): String = when (e) {
    is RegistryError.Network -> stringResource(R.string.reg_error_network, e.reason)
    is RegistryError.Rejected -> stringResource(R.string.reg_error_rejected, e.reason)
    is RegistryError.Storage -> stringResource(R.string.reg_error_storage, e.reason)
}

private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24 * 60L
