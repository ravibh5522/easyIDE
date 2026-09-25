package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.lsp.servers.LanguageServerRows
import dev.easyide.app.lsp.servers.RejectReason
import dev.easyide.app.lsp.servers.ServerIssue
import dev.easyide.app.lsp.servers.ServerRow
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.KitToggle
import dev.easyide.app.ui.kit.Tone

/** The add/edit form's subject: a new custom server, or the existing entry [key]. */
private data class ServerForm(val key: String?)

/**
 * Language servers (customization.md sec 12): every resolved server for the selected layer tab with
 * its source, languages, command and on/off switch; custom servers can be added, edited and removed;
 * `lsp.servers` entries that are ignored are listed with why. Every edit writes the tab's layer. A
 * page, not a dialog; the server limits below it are the category's ordinary rows.
 */
@Composable
internal fun LanguageServersPage(viewModel: SettingsViewModel, env: PageEnv, tab: LayerTab) {
    val state by viewModel.languageServers.state.collectAsStateWithLifecycle()
    val controller = viewModel.languageServers
    var form by remember { mutableStateOf<ServerForm?>(null) }
    val layerName = stringResource(layerLabel(tab.layer))
    val issues = state?.view?.issues.orEmpty()
    val rows = state?.view?.rows.orEmpty()

    Row(Modifier.pageGutter(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        BodyText(
            stringResource(R.string.lsp_screen_layer, layerName) + " " +
                (state?.environmentId?.let { stringResource(R.string.lsp_screen_env, it) } ?: stringResource(R.string.lsp_screen_no_env)),
            Modifier.weight(1f),
            Tone.Neutral,
        )
        KitButton(stringResource(R.string.lsp_add), { form = ServerForm(null) }, style = KitButtonStyle.Secondary, enabled = state != null)
    }
    if (issues.isNotEmpty()) KitSection(stringResource(R.string.lsp_issues), count = issues.size, collapsible = true) { issues.forEach { IssueRow(it) } }
    if (rows.isEmpty()) {
        KitEmptyState(EmptyArt.Prompt, stringResource(R.string.lsp_none))
    } else {
        KitSection(stringResource(R.string.settings_category_language_servers), count = rows.size, collapsible = true) {
            rows.forEach { row ->
                ServerListRow(row, { controller.setEnabled(row.key, it) }, { form = ServerForm(row.key) }, { controller.remove(row.key) })
            }
        }
    }
    CategoryRows(pageSettings(SettingsCategory.LANGUAGE_SERVERS, env.settings), env, stringResource(R.string.lsp_limits_section))

    form?.let { f ->
        ServerFormDialog(
            key = f.key,
            initial = LanguageServerRows.formText(f.key?.let { state?.layerValue?.get(it) }),
            onSave = { key, languages, command -> controller.saveCustom(key, languages, command, f.key) },
            onDismiss = { form = null },
        )
    }
}

@Composable
private fun ServerListRow(row: ServerRow, onEnabled: (Boolean) -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    val source = row.extensionId?.let { stringResource(R.string.lsp_source_pack, it) } ?: stringResource(R.string.lsp_source_custom)
    KitRow(
        title = row.key,
        subtitle = "$source - ${stringResource(R.string.lsp_languages, row.languages.sorted().joinToString())}",
        mono = true,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
                if (row.isCustom) KitTag(stringResource(R.string.lsp_tag_custom), tone = Tone.Accent)
                else if (row.customized) KitTag(stringResource(R.string.lsp_tag_changed), tone = Tone.Warning)
                if (row.setInLayer && row.isCustom) KitIconButton(Icons.Filled.Edit, stringResource(R.string.lsp_edit), onEdit)
                if (row.setInLayer) {
                    KitIconButton(if (row.isCustom) Icons.Filled.Delete else Icons.Filled.Restore, stringResource(if (row.isCustom) R.string.lsp_remove else R.string.lsp_reset_here), onRemove)
                }
                KitToggle(row.enabled, onEnabled)
            }
        },
        id = "lsp-server:${row.key}",
    )
    RowNote(row.command.joinToString(" "), mono = true)
}

@Composable
private fun IssueRow(issue: ServerIssue) {
    val parts = buildList {
        if (RejectReason.NO_LANGUAGES in issue.reasons) add(stringResource(R.string.lsp_issue_no_languages))
        if (RejectReason.NO_COMMAND in issue.reasons) add(stringResource(R.string.lsp_issue_no_command))
        if (issue.reasons.isNotEmpty() && '/' in issue.key) add(stringResource(R.string.lsp_issue_not_declared))
        issue.badFields.forEach { add(stringResource(R.string.lsp_issue_field, it)) }
    }
    KitRow(issue.key, subtitle = parts.joinToString(" - "), mono = true)
}
