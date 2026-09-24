package dev.easyide.extensions

import dev.easyide.extensions.action.ActionOutcome
import dev.easyide.extensions.action.ActionRunner
import dev.easyide.extensions.action.CommandBinding
import dev.easyide.extensions.action.CommandCatalog
import dev.easyide.extensions.action.CommandHandler
import dev.easyide.extensions.action.CommandOutcome
import dev.easyide.extensions.action.ExtensionLog
import dev.easyide.extensions.action.HostPort
import dev.easyide.extensions.action.LogicHost
import dev.easyide.extensions.contrib.ContributionRegistry
import dev.easyide.extensions.contrib.Contributions
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.contrib.RegisteredExtension
import dev.easyide.extensions.host.ActivationManager
import dev.easyide.extensions.host.ActivationState
import dev.easyide.extensions.host.Activator
import dev.easyide.extensions.host.Clock
import dev.easyide.extensions.host.CrashJournal
import dev.easyide.extensions.host.EnabledSet
import dev.easyide.extensions.host.ExtensionHost
import dev.easyide.extensions.host.ExtensionInventory
import dev.easyide.extensions.host.SafeModeState
import dev.easyide.extensions.host.StartupVerdict
import dev.easyide.extensions.manifest.ActivationEvent
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.manifest.ManifestParser
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.ParseOptions
import dev.easyide.extensions.schema.ManifestSchema
import dev.easyide.extensions.settings.RuntimeScope
import dev.easyide.extensions.settings.SettingsPort
import dev.easyide.extensions.whenclause.ContextKeyService
import dev.easyide.extensions.whenclause.ContextKeys
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.Locale

/** Everything [ExtensionsRuntime] needs from the app, as ports. */
class RuntimePorts(
    val settings: SettingsPort,
    val inventory: ExtensionInventory,
    val host: HostPort,
    val log: ExtensionLog,
    /** `<files>/extensions/activation-journal.json`. */
    val journalFile: File,
    /** WASM activation and language-server eligibility; empty until those modules are wired. */
    val activators: List<Activator> = emptyList(),
    /** L2 command handlers (`:ext-wasm`); commands of extensions without WASM never reach it. */
    val logic: LogicHost = LogicHost { _, commandId, _ -> CommandOutcome.Failed("no WASM runtime available for $commandId") },
)

/**
 * The facade `:app` constructs (in `AppContainer`) to run extensions: validation, enablement,
 * contributions, when-clause context, actions and activation, wired together.
 *
 * Lifecycle: [start] once at process start, before any UI observes contributions. It reads
 * the crash journal (possibly entering automatic safe mode), discovers and validates
 * installed packages, and keeps the enabled set, contribution stores and context keys in
 * sync with settings, safe mode and the inventory. Coordination runs on one serialized
 * lane of [Dispatchers.Default] so registry updates never interleave.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionsRuntime(
    private val ports: RuntimePorts,
    private val scope: CoroutineScope,
    builtIn: Contributions = Contributions.EMPTY,
    builtInCommands: Set<String> = emptySet(),
    launcherSafeMode: Boolean = false,
    locale: Locale = Locale.getDefault(),
    clock: Clock = Clock { System.currentTimeMillis() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lane = Dispatchers.Default.limitedParallelism(1)
    private val laneLock = Mutex()

    val journal = CrashJournal(ports.journalFile, ports.log)
    val safeMode = SafeModeState(ports.settings, launcherSafeMode, scope)
    val contextKeys = ContextKeyService(ports.settings)
    val contributions = ContributionRegistry(builtIn)

    private val parser = ManifestParser(
        ManifestSchema.validator,
        ParseOptions(locale = locale, builtInCommands = builtInCommands + builtIn.commands.map { it.command }),
    )
    val extensions = ExtensionHost(ports.inventory, ports.settings, safeMode, parser, { PackageLimits.from(ports.settings) }, io)
    val activation = ActivationManager(ports.activators, journal, ports.inventory, ports.settings, clock, ports.log)
    val actions = ActionRunner(Catalog(), ports.host, ports.settings, { contextKeys.snapshot.value }, ports.log, ActivatingLogic())

    lateinit var startupVerdict: StartupVerdict
        private set

    /** Reads the journal, then keeps everything in sync. Call once. */
    fun start() {
        startupVerdict = journal.onStartup()
        if (startupVerdict.enterSafeMode) safeMode.enterAuto(startupVerdict.suspects)
        scope.launch(lane) { ports.inventory.installed.collect { serial { extensions.refresh(it) } } }
        scope.launch(lane) { ports.settings.version.drop(1).collect { serial { extensions.recompute() } } }
        scope.launch(lane) { safeMode.active.collect { reason ->
            contextKeys.set(ContextKeys.isSafeMode, reason != null)
            serial { extensions.recompute() }
        } }
        scope.launch(lane) { extensions.enabled.collect { set -> serial { apply(set) } } }
        scope.launch(lane) { activation.states.collect { states -> publishEnabledKeys(states) } }
    }

    /** Environment or project switched. */
    fun setRuntimeScope(runtime: RuntimeScope) {
        contextKeys.setRuntime(runtime)
        scope.launch(lane) { serial { extensions.setRuntime(runtime) } }
    }

    /** Forwards an activation event (sdk-reference `activationEvents`). */
    fun emit(event: ActivationEvent) {
        scope.launch(lane) { activation.onEvent(event) }
    }

    /**
     * First frame plus `STARTUP_IDLE_DELAY_MS` (the app schedules it): `onStartupFinished`
     * activations, and a clean run resets the crash journal's counter.
     */
    suspend fun onStartupFinished() {
        activation.onEvent(ActivationEvent.OnStartupFinished)
        journal.markCleanRun()
    }

    /** Runs a command through the action engine (the app's command registry calls this). */
    suspend fun run(commandId: String, args: JsonElement? = null): ActionOutcome = actions.run(commandId, args)

    /** Glob patterns the workspace scanner must look for (`workspaceContains:`) among enabled packs. */
    fun workspaceGlobs(): Set<String> = extensions.enabled.value.extensions
        .flatMap { it.descriptor.activationEvents }.filterIsInstance<ActivationEvent.WorkspaceContains>().mapTo(HashSet()) { it.glob }

    private suspend fun serial(block: suspend () -> Unit) = laneLock.withLock { block() }

    /** Journal-wrapped registration of newcomers, then activation bookkeeping. */
    private suspend fun apply(set: EnabledSet) {
        val registered = set.extensions.map { RegisteredExtension(it.id, it.descriptor.version, it.descriptor.contributes) }
        val before = contributions.registered().mapTo(HashSet()) { it.id to it.version }
        val newcomers = registered.filter { (it.id to it.version) !in before }
        newcomers.forEach { journal.begin(it.id, CrashJournal.Phase.REGISTER_CONTRIBUTIONS) }
        try {
            contributions.update(registered)
        } finally {
            newcomers.forEach { journal.end(it.id) }
        }
        val crashDisabled = extensions.loaded.value.filter { it.pkg.crashDisabled }.mapTo(HashSet()) { it.descriptor.id }
        activation.onEnabledSetChanged(set, crashDisabled)
    }

    private fun publishEnabledKeys(states: Map<ExtensionId, ActivationState>) {
        contextKeys.setAll(states.map { (id, s) ->
            ContextKeys.extensionEnabled(id.value).name to JsonPrimitive(s != ActivationState.DISABLED && s != ActivationState.CRASH_DISABLED)
        }.toMap())
    }

    /** Resolves commands to the winning owner's binding, with its granted capabilities. */
    private inner class Catalog : CommandCatalog {
        override fun binding(commandId: String): CommandBinding? {
            val owner = contributions.commandOwner(commandId) as? Owner.Ext ?: return null
            val ext = extensions.enabled.value.byId(owner.id) ?: return null
            val d = ext.descriptor
            val handler = d.actions[commandId]?.let(CommandHandler::Declarative)
                ?: CommandHandler.Logic.takeIf { d.wasm != null } ?: return null
            val title = d.contributes.commands.first { it.command == commandId }.title
            return CommandBinding(owner.id, commandId, title, handler, d.inputs, ext.granted, contributions.ownedSettings(owner), d.guestRoot)
        }
    }

    /** L2 commands activate their extension first (`onCommand`), then dispatch to the WASM host. */
    private inner class ActivatingLogic : LogicHost {
        override suspend fun executeCommand(owner: ExtensionId, commandId: String, args: JsonElement?): CommandOutcome {
            activation.onEvent(ActivationEvent.OnCommand(commandId))
            return when (val s = activation.ensureActive(owner)) {
                ActivationState.ACTIVE -> ports.logic.executeCommand(owner, commandId, args)
                else -> CommandOutcome.Failed("extension ${owner.value} is ${s.name.lowercase()}")
            }
        }
    }
}
