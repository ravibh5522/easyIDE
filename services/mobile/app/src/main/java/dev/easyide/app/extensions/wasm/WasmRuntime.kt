package dev.easyide.app.extensions.wasm

import android.content.ComponentCallbacks2
import dev.easyide.app.extensions.host.WorkspaceBridge
import dev.easyide.app.lsp.ExtensionProviders
import dev.easyide.app.lsp.ProvidedCompletions
import dev.easyide.app.lsp.ProviderQuery
import dev.easyide.extensions.AppApi
import dev.easyide.extensions.ExtensionsRuntime
import dev.easyide.extensions.action.ActionError
import dev.easyide.extensions.action.CommandOutcome
import dev.easyide.extensions.action.ExtensionLog
import dev.easyide.extensions.action.HostPort
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.action.LogLevel
import dev.easyide.extensions.action.LogicHost
import dev.easyide.extensions.capability.CapabilitySet
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.host.ActivationResult
import dev.easyide.extensions.host.Activator
import dev.easyide.extensions.host.ExtensionInventory
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.settings.ExtensionSettings
import dev.easyide.extensions.settings.ExtensionSettings.int
import dev.easyide.extensions.settings.RuntimeScope
import dev.easyide.extensions.settings.SettingsPort
import dev.easyide.extensions.settings.SettingsQuery
import dev.easyide.extensions.whenclause.ContextKeys
import dev.easyide.extwasm.ActivationContext
import dev.easyide.extwasm.CrashPolicy
import dev.easyide.extwasm.EnvInfo
import dev.easyide.extwasm.ErrorCode
import dev.easyide.extwasm.HostResult
import dev.easyide.extwasm.SettingsLookup
import dev.easyide.extwasm.WasmHost
import dev.easyide.extwasm.WasmLimits
import dev.easyide.extwasm.host.HostInfo
import dev.easyide.extwasm.host.HostPorts
import dev.easyide.extwasm.host.WasmExtension
import dev.easyide.extwasm.load.WasmModuleLoader
import dev.easyide.lsp.protocol.CompletionList
import dev.easyide.lsp.protocol.Hover
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import dev.easyide.extwasm.host.CapabilitySet as WasmCapabilities
import dev.easyide.extwasm.host.ExtensionLog as WasmLog
import dev.easyide.extwasm.host.LogLevel as WasmLogLevel

/** Everything the app hands the WASM layer; the composition root fills it once. */
class WasmDeps(
    /** `<files>/extensions`: holds `wasm-cache/`, `storage/` and `wasm-modules.json`. */
    val extensionsDir: File,
    val settings: SettingsPort,
    /** The L1 host: prompts, stage reveal, and the open workspace ([workspace]). */
    val host: HostPort,
    val workspace: () -> WorkspaceBridge?,
    /** Host dir of an environment's rootfs (guest `/` for `fs.outsideProject`). */
    val rootfs: (envId: String) -> File?,
    val clipboard: ClipboardAccess,
    val log: ExtensionLog,
    /** Crash-loop disables are persisted through it (the same path as other crash disables). */
    val inventory: ExtensionInventory,
    val info: HostInfo,
    val scope: CoroutineScope,
    val io: CoroutineDispatcher,
    val main: CoroutineDispatcher = Dispatchers.Main,
    val clock: () -> Long = System::currentTimeMillis,
)

/**
 * The app side of the L2 layer (lld/wasm-host.md sec 12, 14): one [WasmHost] for the
 * process with every host port bridged to app services, plugged into the runtime as its
 * [LogicHost] (L2 command handlers) and an [Activator] (instantiate + `ext_activate` on the
 * first matching activation event, deactivate on disable, uninstall or leaving the
 * environment). Also the fan-out for workspace/config events, completion/hover providers,
 * memory pressure, and the `extensions.wasm.enabled` master switch.
 *
 * Nothing here runs guest code on the main thread; the host moves every call to the
 * extension's own worker.
 */
class WasmRuntime(private val deps: WasmDeps, private val runtime: () -> ExtensionsRuntime) : LogicHost, ExtensionProviders {

    private val lookup = SettingsLookup { key -> deps.settings.value(key, GLOBAL) }
    private val shas = ModuleShas(File(deps.extensionsDir, MODULE_RECORD))
    private val activated: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Status items and view data WASM extensions set at runtime. */
    val ui = WasmUiState()
    val files = WasmFilePort(::guestRoots, { WasmLimits.resolve(lookup).maxMessageBytes / 2 }, deps.io)
    val commands = WasmCommandPort(runtime)

    val ports = HostPorts(
        info = deps.info,
        log = WasmLog { id, level, message -> deps.log.append(LogEntry(ExtensionId.parse(id), LogLevel.valueOf(level.name), message)) },
        editor = WasmEditorPort(deps.workspace),
        files = files,
        ui = WasmUiPort(deps.host, deps.host, ui, ::displayName),
        commands = commands,
        config = WasmConfigPort(deps.settings, ::query),
        lsp = WasmLspPort(deps.workspace) { key -> runtime().contextKeys.snapshot.value[key] },
        sandbox = WasmSandboxPort(deps.workspace, { deps.settings.int(ExtensionSettings.ACTIONS_EXEC_TIMEOUT_SEC) * MS_PER_SEC }, deps.scope, deps.io),
        net = WasmNetPort(deps.scope, deps.io),
        storage = WasmStoragePort(File(deps.extensionsDir, STORAGE_DIR), ::runtimeScope, deps.io),
        secrets = NoSecrets,
        clipboard = WasmClipboardPort(deps.clipboard, deps.main),
    )

    val host = WasmHost(
        loader = WasmModuleLoader(File(deps.extensionsDir, CACHE_DIR)),
        ports = ports,
        settings = lookup,
        crashes = CrashPolicy(::disableForCrashLoop),
        scope = deps.scope,
        clock = deps.clock,
    )

    /** Makes enabled `easyide.wasm` extensions live; registered in `RuntimePorts.activators`. */
    val activator: Activator = object : Activator {
        override fun handles(d: ExtensionDescriptor): Boolean = d.wasm != null

        override suspend fun activate(d: ExtensionDescriptor, granted: CapabilitySet): ActivationResult {
            if (!enabled()) return ActivationResult.Failed("WASM extensions are turned off (extensions.wasm.enabled)")
            val ext = try {
                extensionOf(d, granted)
            } catch (e: IOException) {
                return ActivationResult.Failed("cannot read the WASM module: ${e.message}")
            }
            return when (val r = host.activate(ext, contextFor(d))) {
                is HostResult.Ok -> { activated += ext.id; ActivationResult.Ok }
                is HostResult.Err -> ActivationResult.Failed("${r.code}: ${r.message}")
            }
        }

        override suspend fun deactivate(d: ExtensionDescriptor) = drop(d.id.value)
    }

    /** Starts the config and master-switch observers. Call once, after the runtime started. */
    fun start() {
        deps.scope.launch {
            deps.settings.version.drop(1).collect {
                if (!enabled()) activated.toList().forEach { drop(it) } else post(CONFIG_DID_CHANGE, EMPTY)
            }
        }
        deps.scope.launch(deps.io) { shas.prune() }
    }

    // ---- LogicHost: L2 commands, after the runtime activated the owner

    override suspend fun executeCommand(owner: ExtensionId, commandId: String, args: JsonElement?): CommandOutcome {
        if (!enabled()) return CommandOutcome.Failed("WASM extensions are turned off (extensions.wasm.enabled)", ActionError.UNAVAILABLE)
        if (owner.value !in activated) {
            // Dropped while the runtime still counts it active (the master switch went off and on).
            val ext = runtime().extensions.enabled.value.byId(owner)
                ?: return CommandOutcome.Failed("${owner.value} is not enabled", ActionError.UNAVAILABLE)
            val r = activator.activate(ext.descriptor, ext.granted)
            if (r is ActivationResult.Failed) return CommandOutcome.Failed(r.message, ActionError.UNAVAILABLE)
        }
        val list = when (args) {
            null, JsonNull -> JsonArray(emptyList())
            is JsonArray -> args
            else -> JsonArray(listOf(args))
        }
        return when (val r = host.executeCommand(owner.value, commandId, list, commandContext())) {
            is HostResult.Ok -> CommandOutcome.Done(r.result ?: JsonNull)
            is HostResult.Err -> if (r.code == ErrorCode.E_CANCELLED) CommandOutcome.Cancelled else CommandOutcome.Failed(r.message, errorOf(r.code))
        }
    }

    // ---- events

    /**
     * A workspace, editor or config event for every live instance (the host delivers it only
     * to subscribers and withholds paths an extension may not read). A save also becomes
     * `fs.changed` for extensions watching a matching glob.
     */
    fun post(event: String, data: JsonObject) {
        for (id in activated) host.postEvent(id, event, data)
        if (event == DID_SAVE) {
            val path = (data[PATH] as? JsonPrimitive)?.content ?: return
            val change = buildJsonObject { put(PATH, path); put(TYPE, CHANGED) }
            files.watchersOf(path).filter { it in activated }.forEach { host.postEvent(it, FS_CHANGED, change) }
        }
    }

    /** `onTrimMemory`: from RUNNING_LOW on, idle instances and parsed modules go (arch.md 7.7 step 3). */
    fun onTrimMemory(level: Int) {
        if (shouldTrim(level)) host.trimMemory()
    }

    // ---- providers (completion, hover), merged after LSP by the presenters

    override suspend fun completion(query: ProviderQuery): List<ProvidedCompletions> =
        ask(COMPLETION, query) { id, result -> ProvidedCompletions(id, CompletionList.fromJson(result).items) }.filter { it.items.isNotEmpty() }

    override suspend fun hover(query: ProviderQuery): List<Hover> = ask(HOVER, query) { _, result -> Hover.fromJson(result) }

    private suspend fun <T : Any> ask(kind: String, query: ProviderQuery, parse: (String, JsonElement?) -> T?): List<T> {
        if (!enabled()) return emptyList()
        val targets = host.providers.value.filter { it.kind == kind && (it.languages.isEmpty() || query.languageId in it.languages) }
        if (targets.isEmpty()) return emptyList()
        val params = buildJsonObject {
            put(URI, query.uri)
            put(POSITION, buildJsonObject { put(LINE, query.position.line); put(CHARACTER, query.position.character) })
            put(LANGUAGE_ID, query.languageId)
            put(VERSION, query.version)
            query.triggerCharacter?.let { put(TRIGGER_CHARACTER, it) }
        }
        val deadline = WasmLimits.resolve(lookup).callTimeoutMs
        return coroutineScope {
            targets.map { p ->
                async {
                    // A late or failed provider is simply absent from the merge (wasm-host.md 11.3).
                    val r = withTimeoutOrNull(deadline) { host.providerRequest(p.extensionId, PROVIDER_PREFIX + kind, params) }
                    (r as? HostResult.Ok)?.let { parse(p.extensionId, it.result) }
                }
            }.awaitAll().filterNotNull()
        }
    }

    // ---- helpers

    private fun enabled(): Boolean = WasmLimits.enabled(lookup)

    private suspend fun drop(id: String) {
        activated -= id
        host.deactivate(id)
        ui.clear(id)
    }

    private fun disableForCrashLoop(id: String, reason: String) {
        val extId = ExtensionId.parse(id)
        deps.log.append(LogEntry(extId, LogLevel.ERROR, reason))
        activated -= id
        ui.clear(id)
        if (extId != null) deps.scope.launch { deps.inventory.setCrashDisabled(extId, true) }
    }

    /** @throws IOException when the module cannot be hashed. */
    private fun extensionOf(d: ExtensionDescriptor, granted: CapabilitySet): WasmExtension {
        val spec = checkNotNull(d.wasm)
        val module = File(spec.module.hostPath)
        return WasmExtension(
            id = d.id.value,
            version = d.version.toString(),
            moduleFile = module,
            moduleSha256 = shas.shaFor(module),
            manifestMemoryMb = spec.memoryMb,
            capabilities = WasmCapabilities(granted.items.map { it.id }),
            commands = d.contributes.commands.mapTo(HashSet()) { it.command },
            providerKinds = spec.providers.mapTo(HashSet()) { it.kind },
            views = d.contributes.views.mapTo(HashSet()) { it.id },
            stages = d.contributes.stages.mapTo(HashSet()) { it.id },
            // Own keys after conflict resolution, the same set L1 setConfig treats as own.
            settingKeys = runtime().contributions.ownedSettings(Owner.Ext(d.id)),
        )
    }

    private fun contextFor(d: ExtensionDescriptor): ActivationContext {
        val q = query()
        val own = d.contributes.configuration.associate { p -> p.key to (deps.settings.value(p.key, q) ?: p.default ?: JsonNull) }
        val snapshot = runtime().contextKeys.snapshot.value
        fun key(name: String) = (snapshot[name] as? JsonPrimitive)?.content.orEmpty()
        return ActivationContext(
            apiVersion = AppApi.VERSION.toString(),
            settings = JsonObject(own),
            env = EnvInfo(key(ContextKeys.envId.name), key(ContextKeys.envDistro.name), key(ContextKeys.envArch.name)),
        )
    }

    /** The when-clause facts a command handler may want (arch.md 6.3 step 2), from the live snapshot. */
    private fun commandContext(): JsonObject {
        val snapshot = runtime().contextKeys.snapshot.value
        return JsonObject(COMMAND_CONTEXT_KEYS.mapNotNull { k -> snapshot[k]?.let { k to it } }.toMap())
    }

    private fun runtimeScope(): RuntimeScope = runtime().contextKeys.snapshot.value.runtime

    private fun query(): SettingsQuery {
        val snapshot = runtime().contextKeys.snapshot.value
        return SettingsQuery.of(snapshot.runtime, snapshot.languageId)
    }

    private fun guestRoots(): GuestRoots? {
        val bridge = deps.workspace() ?: return null
        val project = bridge.projectDirectory ?: return null
        return GuestRoots(project, deps.rootfs(bridge.environmentId))
    }

    private fun displayName(id: String): String =
        runtime().extensions.enabled.value.extensions.firstOrNull { it.id.value == id }?.descriptor?.displayName ?: id

    private fun errorOf(code: ErrorCode): ActionError = ActionError.entries.firstOrNull { it.code == code.name } ?: ActionError.INTERNAL

    companion object {
        /** From `TRIM_MEMORY_RUNNING_LOW` (and every background level) instances are dropped. */
        fun shouldTrim(level: Int): Boolean = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW

        const val DID_SAVE = "workspace.didSave"
        const val CONFIG_DID_CHANGE = "config.didChange"
        private const val FS_CHANGED = "fs.changed"
        private const val CHANGED = "changed"
        private const val TYPE = "type"
        private const val PATH = "path"
        private const val URI = "uri"
        private const val POSITION = "position"
        private const val LINE = "line"
        private const val CHARACTER = "character"
        private const val LANGUAGE_ID = "languageId"
        private const val VERSION = "version"
        private const val TRIGGER_CHARACTER = "triggerCharacter"
        private const val COMPLETION = "completion"
        private const val HOVER = "hover"
        private const val PROVIDER_PREFIX = "provider."
        private const val CACHE_DIR = "wasm-cache"
        private const val STORAGE_DIR = "storage"
        private const val MODULE_RECORD = "wasm-modules.json"
        private const val MS_PER_SEC = 1000L
        private val GLOBAL = SettingsQuery(null, null, null)
        private val EMPTY = JsonObject(emptyMap())
        private val COMMAND_CONTEXT_KEYS = listOf(
            ContextKeys.editorLangId.name, ContextKeys.resourcePath.name, ContextKeys.editorHasSelection.name,
            ContextKeys.envId.name, ContextKeys.envState.name,
        )
    }
}
