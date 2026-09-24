package dev.easyide.app.ui.screens.extensions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.extensions.TimedLogEntry
import dev.easyide.extensions.host.ActivationState
import dev.easyide.extensions.host.DisabledReason
import dev.easyide.app.data.settings.SafeModeReason
import dev.easyide.extensions.manifest.Source
import java.text.DateFormat
import java.util.Date

/**
 * Extension management (ECO-02/03/31/32): every built-in and installed pack with its
 * state and enable toggle, per-pack details (capabilities with their prompt text,
 * warnings, the contribution inspector), the safe-mode banner, the Extension Log, and
 * install from a folder or `.easyext` file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(viewModel: ExtensionsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val install by viewModel.install.collectAsStateWithLifecycle()
    val rollback by viewModel.rollback.collectAsStateWithLifecycle()
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val create by viewModel.create.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val developerMode by viewModel.developerMode.collectAsStateWithLifecycle()
    var browsing by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::stageArchive) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(viewModel::stageFolder) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ext_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = {
                    if (browsing && browse.configured) {
                        IconButton(onClick = viewModel::refreshRegistries) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.reg_refresh)) }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.ext_install)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.ext_install_folder)) }, onClick = { menuOpen = false; pickFolder.launch(null) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.ext_install_file)) }, onClick = { menuOpen = false; pickFile.launch(arrayOf(ANY_MIME)) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.create_ext_action)) }, onClick = { menuOpen = false; viewModel.openCreate() })
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            state.safeMode?.let { reason -> item { SafeModeBanner(reason, state.safeModeSuspects.map { it.value }, viewModel::exitSafeMode) } }
            item {
                TabRow(selectedTabIndex = if (browsing) 1 else 0) {
                    Tab(selected = !browsing, onClick = { browsing = false }, text = { Text(stringResource(R.string.ext_tab_installed)) })
                    Tab(selected = browsing, onClick = { browsing = true }, text = {
                        Text(if (browse.updates.isEmpty()) stringResource(R.string.ext_tab_browse) else stringResource(R.string.ext_tab_browse_updates, browse.updates.size))
                    })
                }
            }
            if (browsing) browseSection(browse, viewModel::setQuery, viewModel::openDetail)
            if (!browsing) items(state.rows, key = { it.key }) { row ->
                ExtensionCard(
                    row = row,
                    updateTo = browse.updates[row.id]?.takeIf { row.pkg.source == Source.REGISTRY },
                    revokedReason = browse.revoked["${row.id}@${row.pkg.directory.name}"],
                    expanded = expanded == row.key,
                    onToggleDetails = { expanded = if (expanded == row.key) null else row.key },
                    onEnabled = { viewModel.setEnabled(row, it) },
                    onUninstall = { viewModel.uninstall(row) },
                    onRollback = { viewModel.requestRollback(row) },
                    lineActions = LineActions(viewModel::setHidden, viewModel::move),
                )
            }
            if (!browsing) item { LogSection(state.log, viewModel::clearLog) }
        }
    }

    detail?.let { RegistryDetail(it, viewModel::installFromRegistry, viewModel::forgetPin, viewModel::closeDetail) }

    InstallDialogs(install, state.environments, viewModel)
    CreateExtensionDialogs(create, projects, developerMode, viewModel)
    RollbackDialogs(rollback, viewModel)
}

@Composable
private fun SafeModeBanner(reason: SafeModeReason, suspects: List<String>, onExit: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l)) {
        Column(modifier = Modifier.padding(Spacing.l)) {
            Text(stringResource(R.string.ext_safe_mode_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(when (reason) {
                SafeModeReason.SETTING -> R.string.ext_safe_mode_setting
                SafeModeReason.LAUNCHER_SHORTCUT -> R.string.ext_safe_mode_launcher
                SafeModeReason.AUTO_CRASH -> R.string.ext_safe_mode_auto
            }))
            if (suspects.isNotEmpty()) Text(stringResource(R.string.ext_safe_mode_suspects, suspects.joinToString()))
            TextButton(onClick = onExit) { Text(stringResource(R.string.ext_safe_mode_exit)) }
        }
    }
}

/** What the inspector can do to one contribution line. */
private class LineActions(val setHidden: (InspectorLine, Boolean) -> Unit, val move: (InspectorLine, Int) -> Unit)

@Composable
private fun ExtensionCard(
    row: ExtensionRow,
    updateTo: String?,
    revokedReason: String?,
    expanded: Boolean,
    onToggleDetails: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onUninstall: () -> Unit,
    onRollback: () -> Unit,
    lineActions: LineActions,
) {
    val d = row.loaded?.descriptor
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l).clickable(onClick = onToggleDetails)) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(d?.displayName ?: row.id, style = MaterialTheme.typography.titleMedium)
                    Text("${row.id} ${d?.version ?: row.pkg.directory.name} - ${sourceLabel(row.pkg.source)}", style = MaterialTheme.typography.bodySmall)
                    Text(stateLabel(row), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    // Update check (sec 10): a badge only; installing is the user's tap in Browse.
                    updateTo?.let { Text(stringResource(R.string.reg_update_available, it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary) }
                    revokedReason?.let { Text(stringResource(R.string.ext_revoked_reason, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                if (d != null) Switch(checked = row.userEnabled, onCheckedChange = onEnabled)
            }
            d?.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (expanded) Details(row, onUninstall, onRollback, lineActions)
        }
    }
}

@Composable
private fun Details(row: ExtensionRow, onUninstall: () -> Unit, onRollback: () -> Unit, lineActions: LineActions) {
    val d = row.loaded?.descriptor
    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.s))
    row.problem?.let { p ->
        Section(stringResource(R.string.ext_errors))
        p.errors.forEach { Mono(it.toString()) }
    }
    if (d != null) {
        Section(stringResource(R.string.ext_capabilities))
        if (d.capabilities.items.isEmpty()) Text(stringResource(R.string.ext_capabilities_none))
        d.capabilities.items.sortedBy { it.id }.forEach { c -> Text("${c.id}: ${capabilityPrompt(c, row.pkg.envId)}") }
        Text(stringResource(if (d.scope == dev.easyide.extensions.manifest.InstallScope.GLOBAL) R.string.ext_scope_global else R.string.ext_scope_environment))
    }
    row.loaded?.warnings?.takeIf { it.isNotEmpty() }?.let { w ->
        Section(stringResource(R.string.ext_warnings))
        w.forEach { Mono(it.toString()) }
    }
    Section(stringResource(R.string.ext_contributions))
    if (row.contributions.isEmpty()) Text(stringResource(R.string.ext_contributions_none))
    row.contributions.forEach { line -> ContributionLine(line, lineActions) }
    row.shadowed.forEach { Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (row.pkg.source != Source.BUILT_IN) {
        Row {
            row.rollbackTo?.let { v -> TextButton(onClick = onRollback) { Text(stringResource(R.string.ext_rollback, v)) } }
            TextButton(onClick = onUninstall) { Text(stringResource(R.string.ext_uninstall)) }
        }
    }
}

/** One inspector line: its ref, why it is hidden, its conflicts, and Hide/Show and Move up/down. */
@Composable
private fun ContributionLine(line: InspectorLine, actions: LineActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Mono(line.ref)
            line.hiddenBy?.let { Text(stringResource(R.string.ext_hidden_by, it), style = MaterialTheme.typography.bodySmall) }
            if (!line.hideable) Text(stringResource(R.string.ext_not_hideable), style = MaterialTheme.typography.bodySmall)
            line.conflicts.forEach { Text(it.message, style = MaterialTheme.typography.bodySmall) }
        }
        if (line.location != null) {
            IconButton(onClick = { actions.move(line, -1) }, enabled = line.canMoveUp) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.ext_move_up))
            }
            IconButton(onClick = { actions.move(line, 1) }, enabled = line.canMoveDown) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.ext_move_down))
            }
        }
        if (line.hideable || line.hidden) {
            TextButton(onClick = { actions.setHidden(line, !line.hidden) }) {
                Text(stringResource(if (line.hidden) R.string.ext_unhide else R.string.ext_hide))
            }
        }
    }
}

@Composable
private fun LogSection(log: List<TimedLogEntry>, onClear: () -> Unit) {
    val format = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    Column(modifier = Modifier.fillMaxWidth().padding(Spacing.l)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ext_log_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (log.isNotEmpty()) TextButton(onClick = onClear) { Text(stringResource(R.string.ext_log_clear)) }
        }
        if (log.isEmpty()) Text(stringResource(R.string.ext_log_empty))
        log.forEach { e ->
            val who = e.entry.extensionId?.value?.let { "[$it] " }.orEmpty()
            Mono("${format.format(Date(e.atMs))} ${e.entry.level.name} $who${e.entry.message}")
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.s))
}

@Composable
private fun Mono(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
}

@Composable
private fun sourceLabel(source: Source): String = stringResource(when (source) {
    Source.BUILT_IN -> R.string.ext_source_builtin
    Source.SIDELOAD -> R.string.ext_source_local
    Source.DEV -> R.string.ext_source_dev
    Source.REGISTRY -> R.string.ext_source_registry
    Source.OPEN_VSX -> R.string.ext_source_open_vsx
})

@Composable
private fun stateLabel(row: ExtensionRow): String {
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

private const val ANY_MIME = "*/*"
