package dev.easyide.app.ui.screens.workspace.lsp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.screens.workspace.ChromeButton
import dev.easyide.app.ui.screens.workspace.ChromeButtonStyle
import dev.easyide.app.ui.screens.workspace.codeTextStyle
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.lsp.protocol.DiagnosticSeverity
import dev.easyide.lsp.session.ServerKey

/** Label of each status kind; the one mapping the status item and its menu use. */
internal fun ServerStatusKind.label(): Int = when (this) {
    ServerStatusKind.NOT_INSTALLED -> R.string.lsp_status_not_installed
    ServerStatusKind.STARTING -> R.string.lsp_status_starting
    ServerStatusKind.READY -> R.string.lsp_status_ready
    ServerStatusKind.PAUSED_MEMORY -> R.string.lsp_status_paused_memory
    ServerStatusKind.OVER_BUDGET -> R.string.lsp_status_over_budget
    ServerStatusKind.CRASHED -> R.string.lsp_status_crashed
    ServerStatusKind.STOPPED -> R.string.lsp_status_stopped
    ServerStatusKind.DISABLED -> R.string.lsp_status_disabled
    ServerStatusKind.ENVIRONMENT_NOT_READY -> R.string.lsp_status_env_not_ready
}

/**
 * Status bar items (LSP-11, LSP-20): the servers of the active language with a menu of
 * restart / stop / start / install / log, and the diagnostic counts, which open Problems.
 */
@Composable
fun RowScope.LspStatusItems(controller: WorkspaceLspController) {
    val colors = editorColors
    val statuses by controller.statuses.collectAsState()
    val counts by controller.diagnostics.counts.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var logOf by remember { mutableStateOf<ServerKey?>(null) }

    Row(
        modifier = Modifier.clickable { controller.togglePanel(LspPanel.PROBLEMS) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        for ((severity, n) in listOf(DiagnosticSeverity.ERROR to counts.errors, DiagnosticSeverity.WARNING to counts.warnings)) {
            Icon(LspIcons.severity(severity), contentDescription = null, tint = colors.statusBarText, modifier = Modifier.size(LspUiMetrics.statusIconSize))
            Text(n.toString(), style = MaterialTheme.typography.labelSmall, color = colors.statusBarText)
        }
    }
    val summary = ServerStatusKind.summary(statuses.map { it.kind }) ?: return
    Box {
        Text(
            text = stringResource(R.string.lsp_status_item, statuses.joinToString { it.key.serverId.substringAfterLast('/') }, stringResource(summary.label())),
            style = MaterialTheme.typography.labelSmall,
            color = if (summary.isProblem) colors.warning else colors.statusBarText,
            modifier = Modifier.clickable { menuOpen = true },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            for (s in statuses) ServerMenuSection(s, controller, onLog = { logOf = s.key }, close = { menuOpen = false })
        }
    }
    logOf?.let { key -> ServerLogDialog(key, controller.logOf(key)) { logOf = null } }
}

@Composable
private fun ServerMenuSection(s: ServerStatusUi, controller: WorkspaceLspController, onLog: () -> Unit, close: () -> Unit) {
    val colors = editorColors
    Column(modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs)) {
        Text("${s.key.serverId}: ${stringResource(s.kind.label())}", style = MaterialTheme.typography.labelMedium, color = colors.plainText)
        s.rssKb?.let { Text(stringResource(R.string.lsp_status_memory, it / KB_PER_MB), style = MaterialTheme.typography.labelSmall, color = colors.textMuted) }
        s.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.error) }
    }
    if (s.kind.canRestart) MenuItem(R.string.lsp_action_restart, close) { controller.restart(s.key) }
    if (s.kind == ServerStatusKind.READY || s.kind == ServerStatusKind.STARTING) MenuItem(R.string.lsp_action_stop, close) { controller.stop(s.key) }
    if (s.kind == ServerStatusKind.STOPPED || s.kind == ServerStatusKind.PAUSED_MEMORY) MenuItem(R.string.lsp_action_start, close) { controller.start(s.key) }
    if (s.kind == ServerStatusKind.NOT_INSTALLED) {
        if (s.install != null) MenuItem(R.string.lsp_action_install, close) { controller.install(s) }
        MenuItem(R.string.lsp_action_retry, close) { controller.retryProbe(s.key) }
    }
    MenuItem(R.string.lsp_action_show_log, close, onLog)
}

@Composable
private fun MenuItem(label: Int, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { close(); action() })
}

@Composable
private fun ServerLogDialog(key: ServerKey, lines: List<String>, onDismiss: () -> Unit) {
    val colors = editorColors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lsp_log_title, key.serverId)) },
        text = {
            Box(Modifier.heightIn(max = LspUiMetrics.popupMaxHeight * 2).verticalScroll(rememberScrollState(Int.MAX_VALUE)).horizontalScroll(rememberScrollState())) {
                Text(
                    text = lines.ifEmpty { listOf(stringResource(R.string.lsp_log_empty)) }.joinToString("\n"),
                    style = codeTextStyle().copy(color = colors.plainText),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lsp_close)) } },
    )
}

/**
 * The actionable notice for a server that is not installed (or an environment without Linux):
 * install runs the declaring pack's recipe in a visible terminal; retry probes again.
 */
@Composable
fun LspInstallNotice(controller: WorkspaceLspController) {
    val colors = editorColors
    val notice by controller.installNotice.collectAsState()
    val s = notice ?: return
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.raised).padding(horizontal = Spacing.m, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Icon(LspIcons.severity(DiagnosticSeverity.WARNING), null, tint = colors.warning, modifier = Modifier.size(LspUiMetrics.statusIconSize))
        val text = if (s.kind == ServerStatusKind.ENVIRONMENT_NOT_READY) {
            stringResource(R.string.lsp_notice_env_not_ready, s.key.serverId)
        } else {
            stringResource(if (s.install != null) R.string.lsp_notice_not_installed else R.string.lsp_notice_not_installed_manual, s.command, s.key.serverId)
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.plainText, modifier = Modifier.weight(1f))
        if (s.kind == ServerStatusKind.NOT_INSTALLED) {
            if (s.install != null) ChromeButton(stringResource(R.string.lsp_action_install), style = ChromeButtonStyle.PRIMARY, onClick = { controller.install(s) })
            ChromeButton(stringResource(R.string.lsp_action_retry), onClick = { controller.retryProbe(s.key) })
        }
        ChromeButton(stringResource(R.string.lsp_action_dismiss), onClick = { controller.dismissInstall(s.key) })
    }
}

private const val KB_PER_MB = 1024
