package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.extensions.registry.RegistryError
import dev.easyide.app.extensions.registry.RegistryPolicy
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.extensions.capability.Capability
import dev.easyide.extensions.manifest.InstallScope

/**
 * The Browse tab (registry-and-install.md sec 7): "no registry configured", each registry's
 * index age or error with its reason, search, and one card per extension with its registry
 * badge, version, capabilities and install/update state. Tapping a card opens [RegistryDetail].
 */
fun LazyListScope.browseSection(state: BrowseUiState, onQuery: (String) -> Unit, onOpen: (BrowseItem) -> Unit) {
    if (!state.configured) {
        item { NoRegistry(state.problems) }
        return
    }
    items(state.problems) { Notice(stringResource(R.string.reg_config_problem, it), error = true) }
    items(state.registries, key = { "reg-" + it.id }) { RegistryHeader(it) }
    item {
        OutlinedTextField(
            value = state.query, onValueChange = onQuery, singleLine = true,
            label = { Text(stringResource(R.string.reg_search_hint)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
        )
    }
    if (state.items.isEmpty()) item { Notice(stringResource(R.string.reg_no_results), error = false) }
    items(state.items, key = { "item-" + it.id }) { BrowseCard(it, onOpen) }
}

@Composable
private fun NoRegistry(problems: List<String>) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l)) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.reg_none_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.reg_none_body))
            problems.forEach { Text(stringResource(R.string.reg_config_problem, it), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun RegistryHeader(line: RegistryLine) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l)) {
        val age = line.ageMinutes
        Text(
            when {
                line.refreshing -> stringResource(R.string.reg_refreshing, line.id)
                age == null -> stringResource(R.string.reg_never_fetched, line.id)
                else -> stringResource(R.string.reg_age, line.id, ageText(age))
            },
            style = MaterialTheme.typography.labelLarge,
        )
        if (line.stale) Text(stringResource(R.string.reg_stale, RegistryPolicy.STALE_INDEX_WARN_DAYS.toInt()), color = MaterialTheme.colorScheme.error)
        line.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun BrowseCard(item: BrowseItem, onOpen: (BrowseItem) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l).clickable { onOpen(item) }) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                item.registryId?.let { Text(stringResource(R.string.reg_badge, it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            }
            val e = item.entry
            Text(
                listOfNotNull(item.id, e?.version?.toString(), item.installedVersion?.let { stringResource(R.string.reg_installed, it) }).joinToString(" - "),
                style = MaterialTheme.typography.bodySmall,
            )
            item.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (e == null) {
                Text(stringResource(R.string.reg_incompatible), color = MaterialTheme.colorScheme.error)
            } else {
                Text(
                    if (e.capabilities.isEmpty()) stringResource(R.string.ext_capabilities_none) else stringResource(R.string.reg_capabilities_count, e.capabilities.size) + ": " + e.capabilities.joinToString(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (item.updateAvailable && e != null) {
                Text(stringResource(R.string.reg_update_available, e.version.toString()), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Detail sheet: everything the signed entry says, the device's pin, and Install / Update. */
@Composable
fun RegistryDetail(item: BrowseItem, onInstall: (BrowseItem) -> Unit, onForgetPin: (BrowseItem) -> Unit, onDismiss: () -> Unit) {
    val e = item.entry
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.displayName) },
        text = {
            Column(modifier = Modifier.heightIn(max = SHEET_MAX_DP.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(item.id, style = MaterialTheme.typography.bodySmall)
                item.registryId?.let { Text(stringResource(R.string.reg_badge, it), color = MaterialTheme.colorScheme.primary) }
                item.description?.let { Text(it) }
                Text(stringResource(R.string.reg_detail_versions, item.versions.joinToString()))
                if (e == null) {
                    Text(stringResource(R.string.reg_incompatible), color = MaterialTheme.colorScheme.error)
                } else {
                    Text(stringResource(R.string.reg_detail_license, e.license))
                    if (e.categories.isNotEmpty()) Text(stringResource(R.string.reg_detail_categories, e.categories.joinToString()))
                    Text(stringResource(if (e.scope == InstallScope.GLOBAL) R.string.ext_scope_global else R.string.ext_scope_environment))
                    Text(stringResource(R.string.reg_detail_published, e.publishedAt.toString()))
                    Text(stringResource(R.string.ext_capabilities), style = MaterialTheme.typography.titleSmall)
                    if (e.capabilities.isEmpty()) Text(stringResource(R.string.ext_capabilities_none))
                    e.capabilities.forEach { raw -> Text("- " + (Capability.parse(raw)?.let { capabilityPrompt(it, null) } ?: raw)) }
                    Text(stringResource(R.string.reg_detail_signed, e.signature.keyId), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.reg_detail_package, e.size, e.sha256), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                item.pinnedKey?.let { pin ->
                    Text(stringResource(R.string.reg_pinned, item.id.substringBefore('.'), pin), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.reg_forget_pin_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { onForgetPin(item) }) { Text(stringResource(R.string.reg_forget_pin)) }
                }
            }
        },
        confirmButton = {
            val installed = item.installedVersion != null && item.installedVersion == e?.version?.toString()
            TextButton(onClick = { onInstall(item) }, enabled = e != null && item.registryId != null && !installed) {
                Text(
                    when {
                        installed -> stringResource(R.string.reg_reinstalled)
                        item.installedVersion != null && e != null -> stringResource(R.string.reg_update, e.version.toString())
                        else -> stringResource(R.string.reg_install)
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ext_prompt_close)) } },
    )
}

@Composable
private fun Notice(text: String, error: Boolean) {
    Text(
        text, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ageText(minutes: Long): String = when {
    minutes < 1 -> stringResource(R.string.reg_age_now)
    minutes < MINUTES_PER_HOUR -> stringResource(R.string.reg_age_minutes, minutes.toInt())
    minutes < MINUTES_PER_DAY -> stringResource(R.string.reg_age_hours, (minutes / MINUTES_PER_HOUR).toInt())
    else -> stringResource(R.string.reg_age_days, (minutes / MINUTES_PER_DAY).toInt())
}

@Composable
private fun errorText(e: RegistryError): String = when (e) {
    is RegistryError.Network -> stringResource(R.string.reg_error_network, e.reason)
    is RegistryError.Rejected -> stringResource(R.string.reg_error_rejected, e.reason)
    is RegistryError.Storage -> stringResource(R.string.reg_error_storage, e.reason)
}

private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24 * 60L
