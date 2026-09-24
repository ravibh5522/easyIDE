package dev.easyide.app.lsp.servers

import dev.easyide.lsp.session.FeatureFilter
import dev.easyide.lsp.session.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/** One `easyide.sandbox.install` step: a shell string run in a visible terminal. */
data class InstallStep(val title: String, val run: String)

/**
 * How to put a server's binaries into an environment, from the declaring extension's
 * `easyide.sandbox` (sdk-reference): [steps] in order, then [verify], which must exit 0.
 */
data class InstallRecipe(val steps: List<InstallStep>, val verify: String?) {
    /** The whole recipe as one shell line, stopping at the first failing step. */
    fun script(): String = (steps.map { it.run } + listOfNotNull(verify)).joinToString(" && ")
}

/**
 * A language server as its source declares it (`easyide.languageServers[]`), variables such
 * as `${extensionPath}` already expanded by that source. Null limits take the same-named
 * `lsp.*` defaults when resolved.
 *
 * @property key the global server key: `<extId>/<id>` for an extension's server.
 * @property extensionId the declaring extension, shown in status and install notices.
 */
data class ServerDeclaration(
    val key: String,
    val languages: Set<String>,
    val command: List<String>,
    val env: Map<String, String> = emptyMap(),
    val initializationOptions: JsonElement? = null,
    val settingsSection: String? = null,
    val rootMarkers: List<String> = DEFAULT_ROOT_MARKERS,
    val memoryBudgetMb: Int? = null,
    val idleShutdownSec: Int? = null,
    val startupTimeoutSec: Int? = null,
    val features: FeatureFilter = FeatureFilter.ALL,
    val priority: Int = 0,
    val install: InstallRecipe? = null,
    val extensionId: String? = null,
) {
    companion object {
        /** sdk-reference default for `rootMarkers`. */
        val DEFAULT_ROOT_MARKERS = listOf(".git")
    }
}

/**
 * A source of declared servers for one environment. The extension runtime registers one
 * (contributed `languageServers` of the extensions enabled there); the user's `lsp.servers`
 * setting is layered on top by [ServerRegistry], not a provider, because it overrides as well
 * as adds.
 */
fun interface ServerDeclarationProvider {
    fun declarations(environmentId: String): Flow<List<ServerDeclaration>>
}

/** A resolved server plus what the UI needs beyond [ServerConfig]. */
data class ResolvedServer(
    val config: ServerConfig,
    val install: InstallRecipe?,
    val extensionId: String?,
)

/** The `lsp.*` values a declaration falls back to (sdk-reference "Settings keys"). */
data class ServerDefaults(val memoryBudgetMb: Int, val idleShutdownSec: Int, val startupTimeoutSec: Int)
