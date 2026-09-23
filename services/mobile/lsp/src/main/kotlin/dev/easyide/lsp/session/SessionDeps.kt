package dev.easyide.lsp.session

import dev.easyide.lsp.LspSettings
import dev.easyide.lsp.diagnostics.DiagnosticStore
import dev.easyide.lsp.docs.DocumentStore
import dev.easyide.lsp.protocol.ClientUi
import dev.easyide.lsp.protocol.Milestone
import dev.easyide.lsp.workspace.PathMapper
import dev.easyide.lsp.workspace.WorkspaceEditApplier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow

/** Who the client is, as sent in `initialize` (`clientInfo`, `locale`, capabilities inputs). */
data class ClientInfo(
    val name: String,
    val version: String,
    val locale: String,
    val milestone: Milestone,
    val ui: ClientUi,
)

/**
 * Everything one [LspSession] uses; built by [dev.easyide.lsp.manager.LanguageServerManager]
 * per (environment, project) so sessions of one project share the store and diagnostics.
 *
 * @property sessionDispatcher parent of each session's serial dispatcher (`limitedParallelism(1)`).
 */
class SessionDeps(
    val launcher: ServerLauncher,
    val mapper: PathMapper,
    val store: DocumentStore,
    val diagnostics: DiagnosticStore,
    val configuration: ConfigurationProvider,
    val ui: LspUi,
    val edits: WorkspaceEditApplier,
    val logSink: LspLogSink,
    val clock: Clock,
    val settings: StateFlow<LspSettings>,
    val client: ClientInfo,
    val workspaceName: String,
    val sessionDispatcher: CoroutineDispatcher,
    val ioDispatcher: CoroutineDispatcher,
)
