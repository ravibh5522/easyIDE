package dev.easyide.lsp.manager

import dev.easyide.lsp.LspSettings
import dev.easyide.lsp.diagnostics.DiagnosticStore
import dev.easyide.lsp.docs.DocumentStore
import dev.easyide.lsp.session.ClientInfo
import dev.easyide.lsp.session.Clock
import dev.easyide.lsp.session.ConfigurationProvider
import dev.easyide.lsp.session.LspLogSink
import dev.easyide.lsp.session.LspSession
import dev.easyide.lsp.session.LspUi
import dev.easyide.lsp.session.MemoryProbe
import dev.easyide.lsp.session.ProjectLocator
import dev.easyide.lsp.session.ServerLauncher
import dev.easyide.lsp.session.SessionDeps
import dev.easyide.lsp.workspace.EditPort
import dev.easyide.lsp.workspace.WorkspaceEditApplier
import dev.easyide.lsp.workspace.WorkspacePathMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/** The editor/file primitives of one project, from the app's workspace bridge. */
fun interface EditPortFactory {
    fun forProject(environmentId: String, projectId: String): EditPort
}

/** Every port the manager and its sessions use; the app's composition root builds one. */
class ManagerDeps(
    val launcher: ServerLauncher,
    val locator: ProjectLocator,
    val configuration: ConfigurationProvider,
    val ui: LspUi,
    val edits: EditPortFactory,
    val logSink: LspLogSink,
    val memoryProbe: MemoryProbe,
    val clock: Clock,
    val settings: StateFlow<LspSettings>,
    val client: ClientInfo,
    val sessionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
)

internal data class ProjectKey(val environmentId: String, val projectId: String)

/**
 * Shared state of one (environment, project): its document store, diagnostics, path mapper
 * and sessions. [sessions] is replaced (never mutated) on the manager dispatcher and read
 * from any thread, in config order.
 */
internal class ProjectContext(val key: ProjectKey, deps: ManagerDeps) {
    val store = DocumentStore()
    val diagnostics = DiagnosticStore()
    val mapper = WorkspacePathMapper(
        projectDir = deps.locator.projectDir(key.environmentId, key.projectId),
        rootfsDir = deps.locator.rootfsDir(key.environmentId),
        guestWorkspace = deps.locator.guestWorkspace,
        passthroughMounts = deps.locator.passthroughMounts,
    )
    val applier = WorkspaceEditApplier(mapper, store, deps.edits.forProject(key.environmentId, key.projectId))
    val sessionDeps = SessionDeps(
        launcher = deps.launcher,
        mapper = mapper,
        store = store,
        diagnostics = diagnostics,
        configuration = deps.configuration,
        ui = deps.ui,
        edits = applier,
        logSink = deps.logSink,
        clock = deps.clock,
        settings = deps.settings,
        client = deps.client,
        workspaceName = deps.locator.projectName(key.environmentId, key.projectId),
        sessionDispatcher = deps.sessionDispatcher,
        ioDispatcher = deps.ioDispatcher,
    )

    @Volatile var sessions: Map<String, LspSession> = emptyMap()

    // Manager-dispatcher confined:
    var seenUris: Set<String> = emptySet()
    var visible: Set<String> = emptySet()
    var focused: String? = null
}
