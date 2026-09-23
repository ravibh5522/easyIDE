package dev.easyide.lsp.session

import dev.easyide.lsp.protocol.LspFeature
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

/**
 * Session key (lsp-lifecycle.md 2.1). One server per project, never shared: every project is
 * bound at the same guest `/workspace`, so one process can see only one of them.
 *
 * @property serverId `<extId>/<id>` for contributed servers, or a user `lsp.servers` key.
 */
data class ServerKey(val environmentId: String, val projectId: String, val serverId: String) {
    override fun toString(): String = "$serverId@$environmentId/$projectId"
}

/** `features: {only?, exclude?}` from sdk-reference; `only == null` means "every feature". */
data class FeatureFilter(val only: Set<LspFeature>?, val exclude: Set<LspFeature>) {
    fun allows(feature: LspFeature): Boolean = (only == null || feature in only) && feature !in exclude

    companion object {
        val ALL = FeatureFilter(null, emptySet())
    }
}

/**
 * One server definition, fully resolved by the app from `easyide.languageServers` plus the
 * `lsp.servers` layers: `${...}` variables expanded, per-server defaults from the same-named
 * `lsp.*` keys applied, project-layer exec-bearing values already gated by project trust.
 *
 * @property command guest argv; `command[0]` is looked up on the guest `PATH`.
 * @property env declared variables only; the process gets nothing else from the app.
 * @property rootMarkers the server is eligible when one exists at the project root, or when
 *   the list is empty or contains `.git` (the default).
 */
data class ServerConfig(
    val serverId: String,
    val languages: Set<String>,
    val command: List<String>,
    val env: Map<String, String>,
    val initializationOptions: JsonElement?,
    val settingsSection: String?,
    val rootMarkers: List<String>,
    val memoryBudgetMb: Int,
    val idleShutdownSec: Int,
    val startupTimeoutSec: Int,
    val features: FeatureFilter,
    val priority: Int,
    val enabled: Boolean,
) {
    init {
        require(command.isNotEmpty()) { "server $serverId has an empty command" }
    }

    /** Fields whose change needs a new process (lsp-lifecycle.md 2.1 "config changes"). */
    fun needsRestartComparedTo(old: ServerConfig): Boolean =
        command != old.command || env != old.env || initializationOptions != old.initializationOptions
}

/** Resolved server list per (environment, project); re-emits on any settings-layer change. */
fun interface ServerConfigSource {
    fun serversFor(environmentId: String, projectId: String): StateFlow<List<ServerConfig>>
}
