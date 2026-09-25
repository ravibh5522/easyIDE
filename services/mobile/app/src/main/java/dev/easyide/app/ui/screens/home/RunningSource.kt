package dev.easyide.app.ui.screens.home

import dev.easyide.app.session.WorkspaceRegistry
import dev.easyide.lsp.manager.LanguageServerManager
import dev.easyide.lsp.manager.ServerStatus
import dev.easyide.lsp.session.ServerKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What Home's Running section reads and controls. The two owners of running things (the
 * workspace registry and the language-server manager) expose different shapes, so Home talks to
 * this and a test can stand in for both.
 */
interface RunningSource {
    /** Project ids with a live workspace session, on screen or parked. */
    val liveProjectIds: Flow<Set<String>>

    val servers: Flow<Collection<ServerStatus>>

    /** Ends the workspace and forgets what it stored: shells stop and unsaved buffers are dropped. */
    fun stopSession(projectId: String)

    fun stopServer(key: ServerKey)
}

class LiveRunningSource(
    private val workspaces: WorkspaceRegistry<*>,
    private val manager: LanguageServerManager,
) : RunningSource {
    override val liveProjectIds: Flow<Set<String>> = workspaces.liveIds

    override val servers: Flow<Collection<ServerStatus>> = manager.statuses.map { it.values }

    override fun stopSession(projectId: String) = workspaces.close(projectId)

    override fun stopServer(key: ServerKey) = manager.stop(key)
}
