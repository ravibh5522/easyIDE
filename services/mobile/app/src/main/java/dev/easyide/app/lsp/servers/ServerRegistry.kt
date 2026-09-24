package dev.easyide.app.lsp.servers

import dev.easyide.app.data.settings.LspSettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.lsp.session.ServerConfig
import dev.easyide.lsp.session.ServerConfigSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's [ServerConfigSource]: servers declared by registered providers (the extension
 * runtime's contributed `languageServers`) layered under the user's `lsp.servers`, with the
 * `lsp.*` defaults, `lsp.enabled` per language and safe mode applied ([ServerGates]).
 *
 * Re-emits on any provider or settings change; the manager diffs the lists and restarts only
 * servers whose command, env or initialization options changed.
 */
class ServerRegistry(
    private val settings: (environmentId: String, projectId: String) -> Flow<SettingsSnapshot>,
    private val scope: CoroutineScope,
    /** Safe mode on (any reason): settings-only servers do not start (customization.md sec 11). */
    private val safeMode: Flow<Boolean> = flowOf(false),
    private val onRejected: (List<String>) -> Unit,
) : ServerConfigSource {

    private val providers = MutableStateFlow<List<ServerDeclarationProvider>>(emptyList())
    private val resolved = ConcurrentHashMap<Pair<String, String>, StateFlow<MergeResult>>()
    private val configs = ConcurrentHashMap<Pair<String, String>, StateFlow<List<ServerConfig>>>()

    /**
     * Adds a declaration source; closing the returned handle removes it (the extension
     * runtime closes it when it is torn down, so its servers stop as "config changed").
     */
    fun register(provider: ServerDeclarationProvider): AutoCloseable {
        providers.update { it + provider }
        return AutoCloseable { providers.update { list -> list - provider } }
    }

    /** Resolved servers with install recipes and origin, for notices and the status item. */
    fun resolved(environmentId: String, projectId: String): StateFlow<MergeResult> =
        resolved.getOrPut(environmentId to projectId) {
            val inputs = combine(settings(environmentId, projectId), safeMode.distinctUntilChanged()) { s, safe -> SettingsInputs(s, safe) }
                .distinctUntilChanged()
            combine(declarations(environmentId), inputs) { declared, s ->
                // lsp.enabled is applied per language by the gates, so the merge sees it on.
                s.gates.apply(ServerConfigMerge.merge(declared, s.overrides, s.defaults, lspEnabled = true))
            }
                .distinctUntilChanged()
                .map { it.also { r -> if (r.rejected.isNotEmpty()) onRejected(r.rejected) } }
                .stateIn(scope, SharingStarted.Eagerly, MergeResult(emptyList(), emptyList()))
        }

    override fun serversFor(environmentId: String, projectId: String): StateFlow<List<ServerConfig>> =
        configs.getOrPut(environmentId to projectId) {
            resolved(environmentId, projectId)
                .map { r -> r.servers.map { it.config } }
                .stateIn(scope, SharingStarted.Eagerly, emptyList())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun declarations(environmentId: String): Flow<List<ServerDeclaration>> = providers.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList())
        else combine(list.map { it.declarations(environmentId) }) { parts -> parts.flatMap { it } }
    }

    /** The settings a merge depends on; compared so unrelated settings changes do not re-merge. */
    private data class SettingsInputs(
        val overrides: Map<String, ServerOverride>,
        val defaults: ServerDefaults,
        val gates: ServerGates,
    ) {
        constructor(s: SettingsSnapshot, safeMode: Boolean) : this(
            overrides = ServerConfigMerge.parseOverrides(s[LspSettingsSchema.servers]),
            defaults = ServerDefaults(
                memoryBudgetMb = s[LspSettingsSchema.defaultMemoryBudgetMb],
                idleShutdownSec = s[LspSettingsSchema.idleShutdownSec],
                startupTimeoutSec = s[LspSettingsSchema.startupTimeoutSec],
            ),
            gates = ServerGates.from(s, safeMode),
        )
    }
}
