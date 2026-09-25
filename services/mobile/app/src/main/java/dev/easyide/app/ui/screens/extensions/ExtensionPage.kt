package dev.easyide.app.ui.screens.extensions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.Tone
import dev.easyide.extensions.manifest.Source

/**
 * The extension document `easyide://extension/<id>` (screens.md 4), scaffold-free content for the
 * shell to host: header with the enable switch, then Details, Contributions, Capabilities, Log and
 * Versions. [onOpenTarget] receives a shell target string, such as `easyide://settings/extensions#<id>`,
 * for the shell to open (null hides the link); [onClosed] is the action of the "not installed" state (the id may be
 * stale after an uninstall or a restore). Mount [ExtensionsDialogs] once beside it. This binds the view
 * model; what is drawn is [ExtensionPageContent].
 */
@Composable
fun ExtensionPage(id: String, viewModel: ExtensionsViewModel, onOpenTarget: ((String) -> Unit)?, onClosed: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(TAB_DETAILS) }
    var confirmUninstall by rememberSaveable { mutableStateOf(false) }
    val row = state.rows.byId(id)
    if (row == null) {
        KitEmptyState(EmptyArt.Prompt, stringResource(R.string.extui_not_installed, id), modifier, KitAction(stringResource(R.string.extui_back_to_list), onClosed))
        return
    }
    val updateTo = browse.updates[id]?.takeIf { row.pkg.source == Source.REGISTRY }
    val revokedReason = browse.revoked["$id@${row.pkg.directory.name}"]
    val item = row.toItem(updateTo, revokedReason)
    val update = browse.items.firstOrNull { it.id == id }?.takeIf { updateTo != null }
    val actions = PageActions(
        onEnabled = { viewModel.setEnabled(row, it) },
        onUninstall = { confirmUninstall = true },
        onUpdate = update?.let { { viewModel.installFromRegistry(it) } },
        onRollback = row.rollbackTo?.let { { viewModel.requestRollback(row) } },
        onHide = viewModel::setHidden,
        onMove = viewModel::move,
        onGrant = { capability, granted -> viewModel.setGranted(row, capability, granted) },
        onOpenTarget = onOpenTarget,
    )
    ExtensionPageContent(PageModel(row, item, revokedReason, updateTo, logFor(state.log, id)), tab, { tab = it }, actions, modifier)

    if (confirmUninstall) {
        KitDialog(
            title = stringResource(R.string.extui_uninstall_title, id),
            onDismiss = { confirmUninstall = false },
            confirm = KitAction(stringResource(R.string.ext_uninstall)) { confirmUninstall = false; viewModel.uninstall(row) },
            dismiss = KitAction(stringResource(R.string.ext_prompt_cancel)) { confirmUninstall = false },
            tone = Tone.Danger,
        ) {
            DialogText(stringResource(R.string.extui_uninstall_body, item.version))
        }
    }
}
