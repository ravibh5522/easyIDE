package dev.easyide.app.ui.screens.workspace.golden

import dev.easyide.app.ui.screens.workspace.GitPanelState
import dev.easyide.sandbox.files.FileNode
import dev.easyide.sandbox.git.GitBranch
import dev.easyide.sandbox.git.GitChange
import dev.easyide.sandbox.git.GitChangeType
import dev.easyide.sandbox.git.GitCommit
import dev.easyide.sandbox.git.GitRef
import dev.easyide.sandbox.git.GitRefKind
import dev.easyide.sandbox.git.GitRemoteInfo
import dev.easyide.sandbox.git.GitStatus

/**
 * A 25-commit history with the shapes a graph has to draw: a feature branch merged into main, a hotfix merged
 * mid-way, a branch that forks and is never merged, a remote-only branch, tags, and subjects long enough to
 * ellipsize. Newest first and topological, as the service returns it.
 */
object GitUiFixtures {
    private const val LONG = "fix the commit box draft being lost on rotation when the identity form was dismissed twice in a row"

    private fun commit(id: String, subject: String, vararg parents: String, author: String = "Ravi") =
        GitCommit(id, id.take(7), parents.toList(), subject, "", author, "$author@example.com", 0L)

    val commits = listOf(
        commit("m1000001", "Merge branch 'feature/git-ui' into main", "a1000001", "f1000001"),
        commit("a1000001", LONG, "a2000001"),
        commit("f1000001", "git ui: split commit button with a menu", "f2000001", author = "Asha"),
        commit("a2000001", "update changelog", "a3000001"),
        commit("f2000001", "git ui: lane graph with curved merges and ref chips", "f3000001", author = "Asha"),
        commit("a3000001", "bump the kotlin toolchain", "m2000001"),
        commit("f3000001", "git ui: changes as a tree", "f4000001", author = "Asha"),
        commit("m2000001", "Merge branch 'hotfix/lsp-crash'", "a4000001", "h1000001"),
        commit("f4000001", "explorer: collapse all", "f5000001", author = "Asha"),
        commit("h1000001", "lsp: stop the server before the project closes", "h2000001", author = "Kiran"),
        commit("a4000001", "settings: explain the icon theme", "a5000001"),
        commit("f5000001", "explorer: status letters on rows", "a6000001", author = "Asha"),
        commit("h2000001", "lsp: log the exit code", "a6000001", author = "Kiran"),
        commit("x1000001", "wasm: first cut of the extension host", "a7000001", author = "Kiran"),
        commit("a5000001", "terminal: keep the key row above the keyboard", "a6000001"),
        commit("a6000001", "release 1.2.0", "a7000001"),
        commit("a7000001", "diff: word level highlights", "a8000001"),
        commit("a8000001", "diff: side by side on wide windows", "a9000001"),
        commit("s1000001", "build(deps): bump the gradle plugins", "a9000001", author = "dependabot"),
        commit("a9000001", "add the search panel", "a1000002"),
        commit("a1000002", "extensions: install from a vsix", "a1100001"),
        commit("a1100001", "release 1.1.0", "a1200001"),
        commit("a1200001", "files: rename in place", "a1300001"),
        commit("a1300001", "sandbox: proot by default", "a1400001"),
        commit("a1400001", "initial commit", author = "Ravi"),
    )

    private fun local(name: String, current: Boolean = false) = GitRef(name, GitRefKind.LOCAL, current)
    private fun remote(name: String) = GitRef(name, GitRefKind.REMOTE, false)
    private fun tag(name: String) = GitRef(name, GitRefKind.TAG, false)

    val refs = mapOf(
        "m1000001" to listOf(local("main", current = true), remote("origin/main")),
        "a1000001" to listOf(tag("v1.3.0")),
        "f1000001" to listOf(local("feature/git-ui"), remote("origin/feature/git-ui")),
        "h1000001" to listOf(local("hotfix/lsp-crash"), remote("origin/hotfix/lsp-crash")),
        "x1000001" to listOf(local("experiment/wasm")),
        "a6000001" to listOf(tag("v1.2.0")),
        "s1000001" to listOf(remote("origin/dependabot/gradle-plugins-2.4")),
        "a1100001" to listOf(tag("v1.1.0")),
        "a1400001" to listOf(tag("v1.0.0")),
    )

    private fun change(path: String, type: GitChangeType, staged: Boolean) = GitChange(path, type, staged)

    private val status = GitStatus(
        branch = "main",
        staged = listOf(
            change("services/mobile/app/src/main/kit/KitMenu.kt", GitChangeType.MODIFIED, true),
            change("services/mobile/app/src/main/kit/KitMenuPanel.kt", GitChangeType.ADDED, true),
        ),
        unstaged = listOf(
            change("services/mobile/app/src/main/workspace/SourceControlPane.kt", GitChangeType.MODIFIED, false),
            change("services/mobile/app/src/main/workspace/git/ChangeRow.kt", GitChangeType.MODIFIED, false),
            change("services/mobile/app/src/main/workspace/git/AVeryLongFileNameThatMustEllipsizeInTheNarrowPanel.kt", GitChangeType.MODIFIED, false),
            change("tools/ui-lint.sh", GitChangeType.DELETED, false),
            change("docs/ui-redesign/notes.md", GitChangeType.UNTRACKED, false),
        ),
        conflicting = listOf(change("services/mobile/app/build.gradle.kts", GitChangeType.CONFLICTED, false)),
        isClean = false,
        upstream = "origin/main",
        ahead = 2,
        behind = 1,
    )

    private fun branch(name: String, remote: Boolean) = GitBranch(name, remote, false, "0000000", null)

    val panel = GitPanelState(
        isRepository = true,
        status = status,
        commits = commits,
        refs = refs,
        commitMessage = "explorer and source control like VS Code",
        remotes = listOf(GitRemoteInfo("origin", "https://example.com/easyide.git")),
        branches = refs.values.flatten().filter { it.kind != GitRefKind.TAG }.map { branch(it.name, it.kind == GitRefKind.REMOTE) },
    )

    private fun dir(path: String) = FileNode(path.substringAfterLast('/'), path, isDirectory = true, sizeBytes = 0)
    private fun file(path: String) = FileNode(path.substringAfterLast('/'), path, isDirectory = false, sizeBytes = 1)

    val menuFile = file("src/main/Main.kt")
    val menuDir = dir("src/main")
}
