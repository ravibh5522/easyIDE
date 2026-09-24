package dev.easyide.app.ui.screens.workspace.lsp

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.screens.workspace.codeTextStyle
import dev.easyide.lsp.protocol.DiagnosticSeverity
import dev.easyide.lsp.session.ServerKey

/** Label of each status kind; the one mapping the status item and its dialog use. */
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

/** What a server's row in the servers dialog can do. One list, so the dialog and its test agree. */
internal enum class ServerAction { RESTART, STOP, START, INSTALL, RETRY, SHOW_LOG }

internal fun serverActions(kind: ServerStatusKind, canInstall: Boolean): List<ServerAction> = buildList {
    if (kind.canRestart) add(ServerAction.RESTART)
    if (kind == ServerStatusKind.READY || kind == ServerStatusKind.STARTING) add(ServerAction.STOP)
    if (kind == ServerStatusKind.STOPPED || kind == ServerStatusKind.PAUSED_MEMORY) add(ServerAction.START)
    if (kind == ServerStatusKind.NOT_INSTALLED) {
        if (canInstall) add(ServerAction.INSTALL)
        add(ServerAction.RETRY)
    }
    add(ServerAction.SHOW_LOG)
}

private fun ServerAction.label(): Int = when (this) {
    ServerAction.RESTART -> R.string.lsp_action_restart
    ServerAction.STOP -> R.string.lsp_action_stop
    ServerAction.START -> R.string.lsp_action_start
    ServerAction.INSTALL -> R.string.lsp_action_install
    ServerAction.RETRY -> R.string.lsp_action_retry
    ServerAction.SHOW_LOG -> R.string.lsp_action_show_log
}

/**
 * Status bar items (LSP-11, LSP-20): the servers of the active language, which open a status list
 * (restart / stop / start / install / log), and the diagnostic counts, which open Problems.
 */
@Composable
fun RowScope.LspStatusItems(controller: WorkspaceLspController) {
    val colors = Kit.colors
    val statuses by controller.statuses.collectAsState()
    val counts by controller.diagnostics.counts.collectAsState()
    var listOpen by remember { mutableStateOf(false) }
    var logOf by remember { mutableStateOf<ServerKey?>(null) }
    val tint = ColorFilter.tint(colors.statusBarText)

    Row(
        modifier = Modifier.clickable(role = Role.Button) { controller.togglePanel(LspPanel.PROBLEMS) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Kit.space.xs),
    ) {
        for ((severity, n) in listOf(DiagnosticSeverity.ERROR to counts.errors, DiagnosticSeverity.WARNING to counts.warnings)) {
            Image(LspIcons.severity(severity), null, Modifier.size(LspUiMetrics.statusIconSize), colorFilter = tint)
            BasicText(n.toString(), style = Kit.type.labelSmall.copy(color = colors.statusBarText))
        }
    }
    val summary = ServerStatusKind.summary(statuses.map { it.kind }) ?: return
    BasicText(
        text = stringResource(R.string.lsp_status_item, statuses.joinToString { it.key.serverId.substringAfterLast('/') }, stringResource(summary.label())),
        style = Kit.type.labelSmall.copy(color = if (summary.isProblem) colors.warning else colors.statusBarText),
        modifier = Modifier.clickable(role = Role.Button) { listOpen = true },
    )
    if (listOpen) ServerListDialog(statuses, controller, onLog = { logOf = it }, onDismiss = { listOpen = false })
    logOf?.let { key -> ServerLogDialog(key, controller.logOf(key)) { logOf = null } }
}

@Composable
private fun ServerListDialog(statuses: List<ServerStatusUi>, controller: WorkspaceLspController, onLog: (ServerKey) -> Unit, onDismiss: () -> Unit) {
    KitDialog(
        title = stringResource(R.string.wp_lsp_servers_title),
        onDismiss = onDismiss,
        dismiss = KitAction(stringResource(R.string.lsp_close), onDismiss),
    ) {
        statuses.forEach { s ->
            val memory = s.rssKb?.let { stringResource(R.string.lsp_status_memory, it / KB_PER_MB) }
            KitRow(
                title = s.key.serverId,
                subtitle = listOfNotNull(stringResource(s.kind.label()), memory).joinToString(" - "),
                mono = true,
            )
            s.detail?.let { KitBanner(it, tone = Tone.Danger) }
            FlowRow(Modifier.padding(horizontal = Kit.space.s), horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
                serverActions(s.kind, s.install != null).forEach { action ->
                    KitButton(stringResource(action.label()), {
                        // Every action but the log leaves the list, so the change it makes is what the user sees next.
                        if (action != ServerAction.SHOW_LOG) onDismiss()
                        perform(action, s, controller, onLog)
                    }, style = KitButtonStyle.Ghost)
                }
            }
        }
    }
}

private fun perform(action: ServerAction, s: ServerStatusUi, controller: WorkspaceLspController, onLog: (ServerKey) -> Unit) {
    when (action) {
        ServerAction.RESTART -> controller.restart(s.key)
        ServerAction.STOP -> controller.stop(s.key)
        ServerAction.START -> controller.start(s.key)
        ServerAction.INSTALL -> controller.install(s)
        ServerAction.RETRY -> controller.retryProbe(s.key)
        ServerAction.SHOW_LOG -> onLog(s.key)
    }
}

@Composable
private fun ServerLogDialog(key: ServerKey, lines: List<String>, onDismiss: () -> Unit) {
    KitDialog(
        title = stringResource(R.string.lsp_log_title, key.serverId),
        onDismiss = onDismiss,
        confirm = KitAction(stringResource(R.string.lsp_close), onDismiss),
    ) {
        // Opens on the tail: the newest line is the one being asked about.
        Box(Modifier.heightIn(max = LspUiMetrics.popupMaxHeight * 2).verticalScroll(rememberScrollState(Int.MAX_VALUE)).horizontalScroll(rememberScrollState())) {
            BasicText(
                text = lines.ifEmpty { listOf(stringResource(R.string.lsp_log_empty)) }.joinToString("\n"),
                style = codeTextStyle().copy(color = Kit.colors.plainText),
            )
        }
    }
}

/**
 * The actionable notice for a server that is not installed (or an environment without Linux):
 * install runs the declaring pack's recipe in a visible terminal; retry probes again.
 */
@Composable
fun LspInstallNotice(controller: WorkspaceLspController) {
    val notice by controller.installNotice.collectAsState()
    val s = notice ?: return
    val text = if (s.kind == ServerStatusKind.ENVIRONMENT_NOT_READY) {
        stringResource(R.string.lsp_notice_env_not_ready, s.key.serverId)
    } else {
        stringResource(if (s.install != null) R.string.lsp_notice_not_installed else R.string.lsp_notice_not_installed_manual, s.command, s.key.serverId)
    }
    // One action fits a banner: install when there is a recipe, otherwise probe again after a manual install.
    val action = when {
        s.kind != ServerStatusKind.NOT_INSTALLED -> null
        s.install != null -> KitAction(stringResource(R.string.lsp_action_install)) { controller.install(s) }
        else -> KitAction(stringResource(R.string.lsp_action_retry)) { controller.retryProbe(s.key) }
    }
    KitBanner(text, tone = Tone.Warning, action = action, onDismiss = { controller.dismissInstall(s.key) })
}

private const val KB_PER_MB = 1024
