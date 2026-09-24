package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.screens.workspace.ChromeButton
import dev.easyide.app.ui.screens.workspace.ChromeButtonStyle
import dev.easyide.app.ui.theme.EasyIdeFonts
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors

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
    val colors = editorColors
    val running = operation.status == OperationStatus.RUNNING
    val title = stringResource(
        R.string.git_op_title,
        stringResource(kindLabel(operation.kind)),
        stringResource(statusLabel(operation.status)),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.raised)
            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = if (operation.status == OperationStatus.FAILED) colors.error else colors.plainText,
                modifier = Modifier.weight(1f),
            )
            if (running) {
                TextButton(onClick = onCancel, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Text(stringResource(R.string.git_cancel))
                }
            } else {
                IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.git_dismiss),
                        modifier = Modifier.size(IconSize.m),
                    )
                }
            }
        }

        operation.failure?.let(GitErrorPresenter::explanation)?.let { res ->
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.bodySmall,
                color = colors.plainText,
                modifier = Modifier.padding(bottom = Spacing.xs),
            )
        }
        operation.authHost?.let { host ->
            ChromeButton(
                text = stringResource(R.string.git_add_token_for, host),
                onClick = { onAddToken(host) },
                style = ChromeButtonStyle.PRIMARY,
                modifier = Modifier.minimumInteractiveComponentSize().padding(bottom = Spacing.xs),
            )
        }
        OutputLines(operation.output)
    }
}

@Composable
private fun OutputLines(lines: List<String>) {
    if (lines.isEmpty()) return
    val colors = editorColors
    val listState = rememberLazyListState()
    // Follow the tail while output streams; scrollToItem, not animate, so it
    // needs no reduce-motion special case and never lags behind a fast stream.
    LaunchedEffect(lines.size) { listState.scrollToItem(lines.lastIndex) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = GitUi.outputMaxHeight)) {
        itemsIndexed(lines) { _, line ->
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = EasyIdeFonts.mono,
                color = colors.textMuted,
            )
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
