package dev.easyide.app.extensions.adapters

import dev.easyide.app.lsp.servers.InstallRecipe
import dev.easyide.app.lsp.servers.InstallStep
import dev.easyide.app.lsp.servers.ServerDeclaration
import dev.easyide.app.lsp.servers.ServerDeclarationProvider
import dev.easyide.extensions.ExtensionsRuntime
import dev.easyide.extensions.action.ExtensionLog
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.action.LogLevel
import dev.easyide.extensions.action.PredefinedVariable
import dev.easyide.extensions.action.Template
import dev.easyide.extensions.action.VariableRef
import dev.easyide.extensions.capability.Capability
import dev.easyide.extensions.contrib.LanguageServerContribution
import dev.easyide.extensions.contrib.Owned
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.contrib.SandboxContribution
import dev.easyide.extensions.host.EnabledExtension
import dev.easyide.extensions.host.EnabledSet
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.settings.SettingsPort
import dev.easyide.extensions.settings.SettingsQuery
import dev.easyide.extensions.whenclause.ContextLookup
import dev.easyide.extensions.whenclause.WhenEvaluator
import dev.easyide.lsp.protocol.LspFeature
import dev.easyide.lsp.session.FeatureFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * `easyide.languageServers` of the enabled packs as [ServerDeclaration]s for the LSP
 * registry (extension-runtime.md sec 7: languageServers -> lsp-client.md). Pure, so every
 * rule is a unit test; [Provider] feeds it from the live runtime.
 *
 * A server is declared only for its own environment (servers force `scope: environment`)
 * and only while its pack is enabled with `lsp.spawn` granted, so enabling, disabling or
 * uninstalling a pack starts or stops its servers through the registry's diff. `command`
 * and `env` accept `${extensionPath}` and `${config:...}` (sdk-reference); any other or an
 * unset variable skips the server with a log line rather than launching a guessed argv.
 * The pack's `easyide.sandbox.install` steps whose `when` holds, plus `verify`, become the
 * server's install recipe.
 */
object ContributedServers {

    data class Result(val declarations: List<ServerDeclaration>, val skipped: List<Pair<ExtensionId, String>>)

    fun declarations(
        environmentId: String,
        servers: List<Owned<LanguageServerContribution>>,
        sandboxes: List<Owned<SandboxContribution>>,
        enabled: EnabledSet,
        config: (String) -> JsonElement?,
        context: ContextLookup,
    ): Result {
        val out = ArrayList<ServerDeclaration>()
        val skipped = ArrayList<Pair<ExtensionId, String>>()
        for (owned in servers) {
            val ext = (owned.owner as? Owner.Ext)?.id?.let(enabled::byId) ?: continue
            if (ext.pkg.envId != environmentId) continue
            val s = owned.value
            if (!ext.granted.satisfies(Capability.LspSpawn)) {
                skipped += ext.id to "language server ${s.key} not started: ${Capability.LspSpawn.id} is not granted"
                continue
            }
            val vars = Expander(ext, config)
            val command = s.command.map(vars::expand)
            val env = s.env.mapValues { vars.expand(it.value) }
            val error = vars.error
            if (error != null) {
                skipped += ext.id to "language server ${s.key} not started: $error"
                continue
            }
            val sandbox = sandboxes.firstOrNull { it.owner == owned.owner }?.value
            out += ServerDeclaration(
                key = s.key,
                languages = s.languages.toSet(),
                // No failure recorded, so every value expanded.
                command = command.map { requireNotNull(it) },
                env = env.mapValues { requireNotNull(it.value) },
                initializationOptions = s.initializationOptions.takeIf { it.isNotEmpty() },
                settingsSection = s.settingsSection,
                rootMarkers = s.rootMarkers,
                memoryBudgetMb = s.memoryBudgetMb,
                idleShutdownSec = s.idleShutdownSec,
                startupTimeoutSec = s.startupTimeoutSec,
                features = FeatureFilter(s.featuresOnly?.mapNotNull(LspFeature::fromId)?.toSet(), s.featuresExclude.mapNotNull(LspFeature::fromId).toSet()),
                priority = s.priority,
                install = sandbox?.let { recipe(it, Expander(ext, config), context) { msg -> skipped += ext.id to "install recipe of ${s.key}: $msg" } },
                extensionId = ext.id.value,
            )
        }
        return Result(out, skipped)
    }

    /** Steps whose `when` holds, then `verify`; null when nothing is left or a step cannot be expanded. */
    private fun recipe(sandbox: SandboxContribution, vars: Expander, context: ContextLookup, onError: (String) -> Unit): InstallRecipe? {
        val applicable = sandbox.install.filter { step -> step.`when`?.let { WhenEvaluator.evaluate(it, context) } ?: true }
        val steps = applicable.map { InstallStep(it.title, vars.expand(it.run).orEmpty()) }
        val verify = sandbox.verify?.let(vars::expand)
        vars.error?.let { onError(it); return null }
        return if (steps.isEmpty() && verify == null) null else InstallRecipe(steps, verify)
    }

    /** The variables a server definition may use; remembers the first failure for the log line. */
    private class Expander(private val ext: EnabledExtension, private val config: (String) -> JsonElement?) {
        var error: String? = null
            private set

        fun expand(t: Template): String? {
            val out = StringBuilder()
            for (seg in t.segments) {
                when (seg) {
                    is Template.Segment.Literal -> out.append(seg.text)
                    is Template.Segment.Var -> out.append(value(seg.ref) ?: return null)
                }
            }
            // One argv entry, no shell (ARGV_ELEMENT): a NUL would silently cut it short.
            if (out.contains(NUL)) return fail("'${t.source}' expands to text containing NUL")
            return out.toString()
        }

        private fun value(ref: VariableRef): String? = when {
            ref is VariableRef.Predefined && ref.variable == PredefinedVariable.EXTENSION_PATH -> ext.descriptor.guestRoot
            ref is VariableRef.Config -> when (val v = config(ref.key)) {
                null, JsonNull -> fail("setting '${ref.key}' has no value")
                is JsonPrimitive -> v.content
                else -> fail("setting '${ref.key}' is not a string, number or boolean")
            }
            else -> fail("only \${extensionPath} and \${config:...} are available in a language server definition")
        }

        private fun fail(message: String): String? {
            if (error == null) error = message
            return null
        }
    }

    private const val NUL = '\u0000'

    /**
     * The live source: re-declares on any change of contributions, the enabled set, settings
     * (`${config:}`) or context keys (install-step `when`). Skips are logged once per change
     * of the result, not per emission.
     */
    class Provider(
        private val runtime: ExtensionsRuntime,
        private val settings: SettingsPort,
        private val log: ExtensionLog,
    ) : ServerDeclarationProvider {

        override fun declarations(environmentId: String): Flow<List<ServerDeclaration>> {
            val c = runtime.contributions
            return combine(
                c.languageServers.entries, c.sandbox.entries, runtime.extensions.enabled, settings.version, runtime.contextKeys.snapshot,
            ) { servers, sandboxes, enabled, _, ctx ->
                // Settings of this environment; of its open project too when that is the runtime's scope.
                val query = if (ctx.runtime.envId == environmentId) SettingsQuery.of(ctx.runtime) else SettingsQuery(null, environmentId, null)
                declarations(environmentId, servers, sandboxes, enabled, { settings.value(it, query) }, ctx)
            }
                .distinctUntilChanged()
                .onEach { r -> r.skipped.forEach { (id, message) -> log.append(LogEntry(id, LogLevel.WARN, message)) } }
                .map { it.declarations }
        }
    }
}
