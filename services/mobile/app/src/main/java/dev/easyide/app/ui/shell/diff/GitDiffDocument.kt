package dev.easyide.app.ui.shell.diff

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitProgress
import dev.easyide.app.ui.screens.workspace.git.GitConfirm
import dev.easyide.app.ui.screens.workspace.git.GitConfirmDialog
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.host.documentRenderer
import dev.easyide.app.ui.shell.workspace.LocalWorkspaceEnv
import dev.easyide.sandbox.git.FileDiff
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** The renderer of `git-diff` documents. The tab reads `name (right side)`, the way a diff tab does in VS Code. */
internal fun gitDiffDocument() = documentRenderer(
    title = { uri -> Comparison.of(uri)?.subject?.let { stringResource(R.string.shell_diff_title, it.path.substringAfterLast('/'), it.right.text()) } },
) { uri, modifier -> GitDiffDocument(uri, modifier) }

/**
 * A comparison of one file as a stage document, not a dialog: several can be open at once, one beside the
 * file they belong to. It reads through the provider of the URI's scheme, re-reads when the repository
 * publishes a new status (staging a hunk, an edit, a terminal write), and shows the last diff while it does.
 * Unified lines, or two columns when the document itself is wide enough ([DiffMode]).
 */
@Composable
fun GitDiffDocument(uri: DocumentUri, modifier: Modifier = Modifier) {
    val env = LocalWorkspaceEnv.current
    val host = env.gitCallbacks.git.diff.host
    val provider = host.providers.forUri(uri)
    val subject = provider?.subject(uri)
    if (provider == null || subject == null) {
        Notice(stringResource(R.string.shell_diff_unavailable), modifier)
        return
    }
    val revision by host.revision.collectAsState()
    var outcome by remember(uri.key) { mutableStateOf<DiffOutcome?>(null) }
    LaunchedEffect(uri.key, revision) { outcome = provider.load(uri) }
    val actions = remember(uri.key, provider) { provider.actions(uri) }
    val busy by remember(actions) { actions?.busy ?: flowOf(false) }.collectAsState(false)
    val listState = rememberLazyListState()
    val ready = (outcome as? DiffOutcome.Ready)?.diff as? FileDiff.Text

    BoxWithConstraints(modifier.fillMaxSize().background(Kit.colors.background)) {
        val mode = DiffMode.of(maxWidth.value)
        val model = remember(ready, mode) { ready?.let { DiffModel.of(it, mode) } }
        val open = env.gitCallbacks.onOpenFile.takeIf { subject.right is SideLabel.Index || subject.right is SideLabel.Worktree }
        Column(Modifier.fillMaxSize()) {
            Header(subject, ready, model, listState, open?.let { openFile -> { openFile(subject.path) } })
            DiffBody(outcome, model, subject, actions, busy, listState)
        }
        // A discard is confirmed here, not in the source-control pane, which is not composed while its sheet is closed.
        (env.git.confirm as? GitConfirm.DiscardHunk)?.takeIf { it.path == subject.path }?.let {
            GitConfirmDialog(it, env.gitCallbacks.git.commit::answerConfirm)
        }
    }
}

/** The header reads the scroll position itself, so scrolling recomposes it and not the whole document. */
@Composable
private fun Header(subject: DiffSubject, ready: FileDiff.Text?, model: DiffModel?, listState: LazyListState, onOpenFile: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    val at by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex } }
    val stats = remember(ready) { ready?.let { it.added to it.removed } }
    val nav = model?.takeIf { it.headers.isNotEmpty() }?.let { m ->
        fun step(to: Int?): (() -> Unit)? = to?.let { { scope.launch { listState.animateScrollToItem(it) } } }
        HunkNav(m.hunkAt(at), m.headers.size, step(m.previousHunk(at)), step(m.nextHunk(at)))
    }
    DiffHeader(subject, stats, nav, onOpenFile)
}

@Composable
private fun DiffBody(
    outcome: DiffOutcome?,
    model: DiffModel?,
    subject: DiffSubject,
    actions: HunkActions?,
    busy: Boolean,
    listState: LazyListState,
) {
    when (outcome) {
        null -> Box(Modifier.fillMaxSize().padding(Kit.space.l), contentAlignment = Alignment.Center) { KitProgress(fraction = null) }
        DiffOutcome.Gone -> Notice(stringResource(R.string.shell_diff_gone))
        is DiffOutcome.Failed -> Notice(outcome.message, error = true)
        is DiffOutcome.Ready -> when (val diff = outcome.diff) {
            is FileDiff.Binary -> Notice(stringResource(R.string.git_diff_binary))
            is FileDiff.TooLarge -> Notice(stringResource(R.string.git_diff_too_large, Formatter.formatShortFileSize(LocalContext.current, diff.bytes)))
            is FileDiff.Text -> if (model == null || model.items.isEmpty()) {
                Notice(stringResource(R.string.git_diff_empty))
            } else {
                val paint = rememberDiffPaint(subject.path, model.rows)
                DiffList(remember(model, paint, actions, busy) { DiffListSpec(model, paint, actions, busy) }, listState)
            }
        }
    }
}

@Composable
internal fun Notice(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Box(modifier.fillMaxSize().padding(Kit.space.l), contentAlignment = Alignment.Center) {
        BasicText(text, style = Kit.text.body.copy(color = if (error) Kit.colors.error else Kit.colors.textMuted))
    }
}
