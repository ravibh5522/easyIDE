package dev.easyide.app.ui.shell.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitProgress
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.screens.workspace.PanelGroupHeader
import dev.easyide.app.ui.screens.workspace.git.tint
import dev.easyide.app.ui.shell.DocumentOpener
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.host.documentRenderer
import dev.easyide.app.ui.shell.workspace.LocalWorkspaceEnv
import dev.easyide.sandbox.git.DiffEnd
import dev.easyide.sandbox.git.GitChangeType
import dev.easyide.sandbox.git.GitCommitDetail
import dev.easyide.sandbox.git.GitCommitFile
import dev.easyide.sandbox.git.GitResult
import java.text.DateFormat
import java.util.Date

/** The renderer of `git-commit` documents; the tab reads the short id. */
internal fun gitCommitDocument() = documentRenderer(
    title = { uri -> GitDocuments.commitOf(uri)?.let(GitDocuments::shortId) },
) { uri, modifier -> GitCommitDocument(uri, modifier) }

/**
 * One commit as a stage document: its message, who and when, its parents (each opens that commit), and the
 * files it changed. A file opens its diff (a tap previews, a double tap keeps, the menu sends it to the
 * side), so a commit is read by walking its files. A commit never changes, so it is read once.
 */
@Composable
fun GitCommitDocument(uri: DocumentUri, modifier: Modifier = Modifier) {
    val env = LocalWorkspaceEnv.current
    val rev = GitDocuments.commitOf(uri)
    val commits = env.gitCallbacks.git.diff.host.commits
    val result by produceState<GitResult<GitCommitDetail>?>(null, rev) { value = rev?.let { commits.detail(it) } }
    Box(modifier.fillMaxSize().background(Kit.colors.background)) {
        when (val r = result) {
            null -> if (rev == null) Notice(stringResource(R.string.shell_commit_unavailable)) else Loading()
            is GitResult.Success -> CommitBody(r.value, env.actions.documents)
            is GitResult.Failure -> Notice(r.message, error = true)
            GitResult.NotARepository -> Notice(stringResource(R.string.shell_diff_gone))
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize().padding(Kit.space.l), contentAlignment = Alignment.Center) { KitProgress(fraction = null) }
}

@Composable
private fun CommitBody(detail: GitCommitDetail, opener: DocumentOpener) {
    val commit = detail.commit
    LazyColumn(Modifier.fillMaxSize().padding(bottom = Kit.space.l)) {
        item { CommitMessage(commit.subject, commit.body.removePrefix(commit.subject).trim()) }
        item { CommitMeta(detail, opener) }
        item { PanelGroupHeader(stringResource(R.string.shell_commit_files), detail.files.size) }
        items(detail.files.size) { i -> CommitFileRow(detail, detail.files[i], opener) }
    }
}

@Composable
private fun CommitMessage(subject: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(Kit.space.l)) {
        SelectionContainer {
            Column {
                BasicText(subject, style = Kit.text.heading.copy(color = Kit.colors.plainText))
                if (body.isNotEmpty()) BasicText(body, Modifier.padding(top = Kit.space.s), style = Kit.text.body.copy(color = Kit.colors.plainText))
            }
        }
    }
}

@Composable
private fun CommitMeta(detail: GitCommitDetail, opener: DocumentOpener) {
    val commit = detail.commit
    val whenText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(commit.timestampMillis))
    Column(Modifier.fillMaxWidth().padding(horizontal = Kit.space.l)) {
        MetaLine(stringResource(R.string.shell_commit_author), "${commit.authorName} <${commit.authorEmail}>")
        MetaLine(stringResource(R.string.shell_commit_date), whenText)
        MetaLine(stringResource(R.string.shell_commit_id), commit.id, mono = true)
    }
    commit.parents.forEach { parent ->
        val target = GitDocuments.commitUri(parent)
        KitRow(
            title = GitDocuments.shortId(parent),
            subtitle = stringResource(R.string.shell_commit_parent),
            mono = true,
            onClick = target?.let { { opener.preview(it) } },
            onDoubleClick = target?.let { { opener.keep(it) } },
        )
    }
}

@Composable
private fun MetaLine(label: String, value: String, mono: Boolean = false) {
    val colors = Kit.colors
    Column(Modifier.padding(vertical = Kit.space.xs)) {
        BasicText(label, style = Kit.text.label.copy(color = colors.textMuted))
        SelectionContainer {
            BasicText(value, style = (if (mono) Kit.text.mono else Kit.text.body).copy(color = colors.plainText), overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CommitFileRow(detail: GitCommitDetail, file: GitCommitFile, opener: DocumentOpener) {
    val colors = Kit.colors
    val diff = Comparison(file.path, detail.base, DiffEnd.Rev(detail.commit.id)).uri
    KitRow(
        title = file.path.substringAfterLast('/'),
        subtitle = file.path.substringBeforeLast('/', "").ifEmpty { null },
        mono = true,
        onClick = diff?.let { { opener.preview(it) } },
        onDoubleClick = diff?.let { { opener.keep(it) } },
        trailing = {
            BasicText(
                stringResource(R.string.git_diff_stats, file.added, file.removed),
                style = Kit.text.monoSmall.copy(color = file.type.tint(colors.git)),
            )
        },
    )
}
