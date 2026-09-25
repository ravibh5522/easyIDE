package dev.easyide.app.ui.screens.golden

import dev.easyide.app.ui.screens.home.HomeCallbacks
import dev.easyide.app.ui.screens.home.HomeUiState
import dev.easyide.app.ui.screens.home.ProjectListItem
import dev.easyide.app.ui.screens.home.ProjectMeta
import dev.easyide.app.ui.screens.home.RecentScan
import dev.easyide.app.ui.screens.home.RunningId
import dev.easyide.app.ui.screens.home.RunningItem
import dev.easyide.app.ui.screens.home.RunningKind
import dev.easyide.app.ui.screens.home.RunningPhase
import dev.easyide.lsp.session.ServerKey
import dev.easyide.sandbox.files.RecentFile
import dev.easyide.sandbox.git.GitSummary
import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.ProjectRecord
import dev.easyide.sandbox.model.SandboxBackend
import dev.easyide.sandbox.model.SandboxEnvironment

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/** A realistic Home: six projects with mixed git state, a running session and server, two environments. */
internal object HomeFixtures {
    private val now = System.currentTimeMillis()
    private val ubuntu = SandboxEnvironment("env1", "ubuntu-24", SandboxBackend.PROOT, EnvironmentState.READY, now - 30 * DAY, now - HOUR)
    private val alpine = SandboxEnvironment("env2", "alpine", SandboxBackend.CHROOT, EnvironmentState.FAILED, now - 9 * DAY, now - 2 * DAY)

    private fun project(id: String, name: String, openedAgo: Long, branch: String?, changed: Int, env: SandboxEnvironment = ubuntu, folder: String? = null) =
        ProjectListItem(
            project = ProjectRecord(id, name, env.id, now - 40 * DAY, now - openedAgo, externalFolderUri = folder?.let { "content://tree/$it" }),
            environment = env,
            sharedWithCount = 0,
            meta = ProjectMeta(branch?.let { GitSummary(it, changed) }, null, folder, now),
        )

    val projects = listOf(
        project("1", "easyide-mobile", 4 * MINUTE, "ui-improvements", 7),
        project("2", "api-gateway", 3 * HOUR, "main", 0),
        project("3", "notes", 2 * DAY, null, 0, folder = "Documents/notes"),
        project("4", "dotfiles", 6 * DAY, "master", 2, alpine),
        project("5", "a-project-with-a-really-long-name-that-must-not-wrap-anywhere", 20 * DAY, "feature/very-long-branch-name-here", 0),
        project("6", "scratch", 90 * DAY, "main", 0),
    )

    val recentFiles = listOf(
        RecentFile("services/mobile/app/src/main/java/dev/easyide/app/ui/screens/home/HomePanel.kt", now - 4 * MINUTE),
        RecentFile("docs/ui-redesign/density.md", now - 2 * HOUR),
        RecentFile("README.md", now - 3 * DAY),
    )

    val state = HomeUiState(
        isLoading = false,
        projectCount = projects.size,
        visible = projects,
        all = projects,
        resume = projects.first(),
        resumeScan = RecentScan("1", recentFiles),
        recent = RecentScan("1", recentFiles),
        running = listOf(
            RunningItem(RunningId.Session("1"), RunningKind.SESSION, "sh", "easyide-mobile", RunningPhase.RUNNING, "ubuntu-24", projectId = "1"),
            RunningItem(RunningId.Server(ServerKey("env1", "1", "easyide.python/pyright")), RunningKind.SERVER, "pyright", "easyide-mobile", RunningPhase.RUNNING, rssKb = 312_000, projectId = "1"),
        ),
        environments = listOf(ubuntu, alpine),
    )

    val callbacks = HomeCallbacks(
        onQueryChanged = {}, onSortChanged = {}, onSelect = {}, onResumed = {}, onOpenProject = { _, _ -> },
        onNewProject = {}, onOpenSettings = {}, onInstallLinux = {}, onDialog = {}, onFolderPicked = {},
        onFolderPickFailed = {}, onMessageShown = {}, rename = { _, _ -> }, duplicate = { _, _ -> }, delete = {},
        changeEnvironment = { _, _ -> }, importFolder = { _, _, _ -> }, clone = { _, _, _ -> },
    )
}
