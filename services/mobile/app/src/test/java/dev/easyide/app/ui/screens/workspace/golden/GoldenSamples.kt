package dev.easyide.app.ui.screens.workspace.golden

import dev.easyide.app.ui.screens.workspace.GitPanelState
import dev.easyide.app.ui.screens.workspace.SourceControlCallbacks
import dev.easyide.app.ui.screens.workspace.WorkspaceUiState
import dev.easyide.app.ui.screens.workspace.git.GitBranchController
import dev.easyide.app.ui.screens.workspace.git.GitCommitActions
import dev.easyide.app.ui.screens.workspace.git.GitContext
import dev.easyide.app.ui.screens.workspace.git.GitControllers
import dev.easyide.app.ui.screens.workspace.git.GitDiffController
import dev.easyide.app.ui.screens.workspace.git.GitNetworkRunner
import dev.easyide.app.ui.screens.workspace.git.GitRemoteController
import dev.easyide.app.ui.screens.workspace.git.GitSettingsPort
import dev.easyide.app.ui.screens.workspace.git.GitSettingsValues
import dev.easyide.app.ui.screens.workspace.git.GitTokenSink
import dev.easyide.app.ui.screens.workspace.lsp.NavLocation
import dev.easyide.app.ui.screens.workspace.lsp.Problem
import dev.easyide.app.ui.screens.workspace.lsp.ProblemGroup
import dev.easyide.lsp.protocol.DiagnosticSeverity
import dev.easyide.lsp.protocol.Position
import dev.easyide.lsp.protocol.Range
import dev.easyide.lsp.session.ServerKey
import dev.easyide.sandbox.files.FileNode
import dev.easyide.sandbox.git.GitChange
import dev.easyide.sandbox.git.GitChangeType
import dev.easyide.sandbox.git.GitCommit
import dev.easyide.sandbox.git.GitIdentity
import dev.easyide.sandbox.git.GitRemoteInfo
import dev.easyide.sandbox.git.GitResult
import dev.easyide.sandbox.git.GitService
import dev.easyide.sandbox.git.GitStatus
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Sample project data the workspace goldens draw: enough of every kind of row to show the anatomy, one name long enough to need its ellipsis. */
object GoldenSamples {
    private const val LONG_NAME = "AVeryLongFileNameThatMustEllipsizeInTheNarrowPanel.kt"

    private fun dir(path: String) = FileNode(path.substringAfterLast('/'), path, isDirectory = true, sizeBytes = 0)
    private fun file(path: String) = FileNode(path.substringAfterLast('/'), path, isDirectory = false, sizeBytes = 1)

    val tree = WorkspaceUiState(
        projectName = "easyide",
        tree = listOf(dir("src"), dir("docs"), file("build.gradle.kts"), file("README.md"), file(".gitignore")),
        expandedDirs = setOf("src", "src/main"),
        childrenByDir = mapOf(
            "src" to listOf(dir("src/main"), file("src/App.kt")),
            "src/main" to listOf(file("src/main/Main.kt"), file("src/main/Theme.kt"), file("src/main/$LONG_NAME")),
        ),
        activeTabPath = "src/main/Main.kt",
    )

    val treeChanges = mapOf(
        "src/main/Main.kt" to GitChangeType.MODIFIED,
        "src/main/Theme.kt" to GitChangeType.ADDED,
        "README.md" to GitChangeType.UNTRACKED,
    )

    private fun change(path: String, type: GitChangeType, staged: Boolean) = GitChange(path, type, staged)

    private val status = GitStatus(
        branch = "ui-improvements",
        staged = listOf(change("services/mobile/app/src/kit/KitRow.kt", GitChangeType.MODIFIED, true), change("docs/ui-redesign/density.md", GitChangeType.ADDED, true)),
        unstaged = listOf(
            change("services/mobile/app/src/workspace/FileTreeRow.kt", GitChangeType.MODIFIED, false),
            change("services/mobile/app/src/workspace/$LONG_NAME", GitChangeType.MODIFIED, false),
            change("tools/ui-lint.sh", GitChangeType.DELETED, false),
            change("notes.txt", GitChangeType.UNTRACKED, false),
        ),
        conflicting = emptyList(),
        isClean = false,
        upstream = "origin/ui-improvements",
        ahead = 2,
        behind = 1,
    )

    private fun commit(id: String, parent: String?, subject: String) =
        GitCommit(id, id.take(7), listOfNotNull(parent), subject, subject, "Ravi", "r@example.com", 0L)

    val git = GitPanelState(
        isRepository = true,
        status = status,
        commits = listOf(
            commit("c3c8b5a1", "68a87e2f", "merge r7/densekit: density tokens and row anatomy"),
            commit("68a87e2f", "a229b2b1", "add diff render tests"),
            commit("a229b2b1", null, "update properties, tracker and chainlog for density"),
        ),
        commitMessage = "update the panels to one-line rows",
        remotes = listOf(GitRemoteInfo("origin", "https://example.com/easyide.git")),
    )

    /** Real controllers over inert ports: the panel only reads their state and calls them on a tap, which the goldens never make. */
    val gitCallbacks: SourceControlCallbacks = run {
        val ctx = GitContext(MutableStateFlow(git), GitService(Dispatchers.Unconfined), File("."), CoroutineScope(Job()))
        val settings = object : GitSettingsPort {
            override val values = emptyFlow<GitSettingsValues>()
            override suspend fun saveIdentity(identity: GitIdentity, projectOnly: Boolean) = true
        }
        val remote = GitRemoteController(
            ctx, GitNetworkRunner { _, _, _, _ -> GitResult.Failure("offline") }, settings,
            GitTokenSink { _, _, _ -> true }, MutableStateFlow(false), {},
        )
        val commit = object : GitCommitActions {
            override fun setAmend(amend: Boolean) = Unit
            override fun saveIdentity(name: String, email: String, projectOnly: Boolean) = Unit
            override fun dismissIdentityPrompt() = Unit
            override fun answerConfirm(accepted: Boolean) = Unit
        }
        SourceControlCallbacks({}, {}, {}, {}, {}, {}, {}, {}, GitControllers(remote, GitBranchController(ctx), GitDiffController(ctx), commit))
    }

    private val server = ServerKey("env", "project", "kotlin-language-server")

    private fun problem(path: String, line: Int, severity: DiagnosticSeverity, message: String, code: String) =
        Problem(NavLocation(path, File(path), path, Range(Position(line, 4), Position(line, 9))), severity, message, "kotlin", code, server)

    val problems = listOf(
        ProblemGroup(
            "src/main/Main.kt",
            listOf(
                problem("src/main/Main.kt", 11, DiagnosticSeverity.ERROR, "Unresolved reference: density", "UNRESOLVED_REFERENCE"),
                problem("src/main/Main.kt", 40, DiagnosticSeverity.WARNING, "Variable 'unused' is never used, remove it or use it in a way the compiler can see", "UNUSED_VARIABLE"),
            ),
        ),
        ProblemGroup("src/main/$LONG_NAME", listOf(problem("src/main/$LONG_NAME", 3, DiagnosticSeverity.INFORMATION, "Can be replaced with a property access", "USELESS_CALL"))),
    )
}
