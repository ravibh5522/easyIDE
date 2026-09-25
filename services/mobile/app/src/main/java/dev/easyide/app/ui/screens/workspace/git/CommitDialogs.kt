package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.screens.workspace.DialogText
import dev.easyide.app.ui.screens.workspace.NAME_KEYBOARD
import dev.easyide.app.ui.shell.DocumentOpener
import dev.easyide.app.ui.shell.diff.Comparison
import dev.easyide.sandbox.git.DiffEnd
import dev.easyide.sandbox.git.GitBranch
import dev.easyide.sandbox.git.isValidBranchName
import dev.easyide.sandbox.git.isValidTagName

/** The question a commit-menu entry needs answered, as a dialog; [onDismiss] closes it whether or not it was answered. */
@Composable
internal fun HistoryAskDialog(ask: HistoryAsk, branches: List<GitBranch>, git: GitControllers, onDismiss: () -> Unit) {
    when (ask) {
        is HistoryAsk.Branch -> GitNameDialog(
            title = stringResource(R.string.gitui_branch_at_title, ask.label),
            initial = "",
            label = stringResource(R.string.git_branch_name),
            confirmLabel = stringResource(R.string.git_create_branch),
            isValid = ::isValidBranchName,
            onConfirm = { git.history.createBranchAt(ask.sha, it) },
            onDismiss = onDismiss,
        )
        is HistoryAsk.Tag -> TagDialog(ask, git.history, onDismiss)
        is HistoryAsk.CompareWith -> ComparePicker(ask, branches, git.history, onDismiss)
    }
}

@Composable
private fun TagDialog(ask: HistoryAsk, history: GitHistoryController, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    val valid = isValidTagName(name.trim())
    KitDialog(
        title = stringResource(R.string.gitui_tag_at_title, ask.label),
        onDismiss = onDismiss,
        confirm = if (valid) KitAction(stringResource(R.string.gitui_tag_create)) { history.createTag(ask.sha, name, message); onDismiss() } else null,
        dismiss = KitAction(stringResource(R.string.git_cancel), onDismiss),
    ) {
        KitField(
            name, { name = it },
            label = stringResource(R.string.gitui_tag_name),
            error = if (name.isNotBlank() && !valid) stringResource(R.string.wp_git_invalid_name) else null,
            mono = true,
            keyboard = NAME_KEYBOARD,
        )
        KitField(message, { message = it }, label = stringResource(R.string.gitui_tag_message))
    }
}

/** Every branch as a row: the picked one is what the commit is compared with. */
@Composable
private fun ComparePicker(ask: HistoryAsk, branches: List<GitBranch>, history: GitHistoryController, onDismiss: () -> Unit) {
    KitDialog(
        title = stringResource(R.string.gitui_compare_pick_title, ask.label),
        onDismiss = onDismiss,
        dismiss = KitAction(stringResource(R.string.git_cancel), onDismiss),
    ) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = GitUi.sheetListMaxHeight)) {
            items(branches, key = { it.name }) { branch ->
                KitRow(branch.name, mono = true, onClick = { history.compare(head = branch.name, headLabel = branch.name, base = ask.sha, baseLabel = ask.label); onDismiss() })
            }
        }
    }
}

/** The files that differ between two commits or refs; a row opens that one file's diff. */
@Composable
internal fun ComparisonDialog(comparison: GitComparison, opener: DocumentOpener, onClose: () -> Unit) {
    KitDialog(
        title = stringResource(R.string.gitui_compare_title, comparison.baseLabel, comparison.headLabel),
        onDismiss = onClose,
        dismiss = KitAction(stringResource(R.string.gitui_compare_close), onClose),
    ) {
        if (comparison.files.isEmpty()) DialogText(stringResource(R.string.gitui_compare_empty), muted = true)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = GitUi.sheetListMaxHeight)) {
            items(comparison.files, key = { it.path }) { file ->
                val open = Comparison(file.path, DiffEnd.Rev(comparison.base), DiffEnd.Rev(comparison.head)).uri
                KitRow(
                    title = file.path.substringAfterLast('/'),
                    subtitle = file.path.substringBeforeLast('/', "").ifEmpty { null },
                    onClick = open?.let { uri -> { opener.preview(uri); onClose() } },
                    trailing = { GitStatusLetter(file.type) },
                )
            }
        }
    }
}
