package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.Tone

/**
 * Progress and result of the last fetch/pull/push: git's own output as it
 * streams, a cancel button while it runs, and - on failure - a plain-language
 * cause plus the fix when there is one (a token for the host that refused).
 */
@Composable
internal fun OperationPanel(
    operation: GitOperation,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onAddToken: (host: String) -> Unit,
) {
    val colors = Kit.colors
    val running = operation.status == OperationStatus.RUNNING
    val title = stringResource(
        R.string.git_op_title,
        stringResource(kindLabel(operation.kind)),
        stringResource(statusLabel(operation.status)),
    )
    val explanation = operation.failure?.let(GitErrorPresenter::explanation)?.let { stringResource(it) }
    val addToken = operation.authHost?.let { host -> KitAction(stringResource(R.string.git_add_token_for, host)) { onAddToken(host) } }

    Column(modifier = Modifier.fillMaxWidth().background(colors.raised)) {
        Row(Modifier.padding(start = Kit.space.m), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = title,
                style = Kit.text.title.copy(color = if (operation.status == OperationStatus.FAILED) colors.error else colors.plainText),
                modifier = Modifier.weight(1f),
            )
            if (running) {
                KitButton(stringResource(R.string.git_cancel), onCancel, style = KitButtonStyle.Ghost)
            } else {
                KitIconButton(Icons.Filled.Close, stringResource(R.string.git_dismiss), onDismiss)
            }
        }
        if (explanation != null) {
            KitBanner(explanation, tone = Tone.Danger, action = addToken)
        } else if (addToken != null) {
            KitButton(addToken.label, addToken.onClick, Modifier.padding(horizontal = Kit.space.xs), KitButtonStyle.Secondary)
        }
        OutputLines(operation.output)
    }
}

@Composable
private fun OutputLines(lines: List<String>) {
    if (lines.isEmpty()) return
    val listState = rememberLazyListState()
    // Follow the tail while output streams; scrollToItem, not animate, so it
    // needs no reduce-motion special case and never lags behind a fast stream.
    LaunchedEffect(lines.size) { listState.scrollToItem(lines.lastIndex) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = GitUi.outputMaxHeight).padding(horizontal = Kit.space.m, vertical = Kit.space.xs),
    ) {
        itemsIndexed(lines) { _, line ->
            BasicText(line, style = Kit.text.monoSmall.copy(color = Kit.colors.textMuted))
        }
    }
}

private fun kindLabel(kind: GitOperationKind): Int = when (kind) {
    GitOperationKind.FETCH -> R.string.git_fetch
    GitOperationKind.PULL -> R.string.git_pull
    GitOperationKind.PUSH -> R.string.git_push
    GitOperationKind.PUBLISH -> R.string.git_publish
    GitOperationKind.FORCE_PUSH -> R.string.git_force_push
}

private fun statusLabel(status: OperationStatus): Int = when (status) {
    OperationStatus.RUNNING -> R.string.git_op_running
    OperationStatus.SUCCEEDED -> R.string.git_op_succeeded
    OperationStatus.FAILED -> R.string.git_op_failed
    OperationStatus.CANCELLED -> R.string.git_op_cancelled
}
