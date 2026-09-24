package dev.easyide.extwasm

import dev.easyide.extwasm.host.Cap
import dev.easyide.extwasm.host.GuestPaths
import dev.easyide.extwasm.host.HostCallRouter
import dev.easyide.extwasm.host.HostPorts
import dev.easyide.extwasm.host.InstanceSession
import dev.easyide.extwasm.host.LogLevel
import dev.easyide.extwasm.host.ProviderRegistration
import dev.easyide.extwasm.host.WasmExtension
import dev.easyide.extwasm.load.WasmModuleLoader
import dev.easyide.extwasm.runtime.CrashWindow
import dev.easyide.extwasm.runtime.EventQueue
import dev.easyide.extwasm.runtime.GuestFailure
import dev.easyide.extwasm.runtime.Messages
import dev.easyide.extwasm.runtime.WasmInstance
import dev.easyide.extwasm.runtime.Watchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The L2 host (wasm-host.md sec 12): one lazily created instance and worker per enabled
 * extension, ABI v1 message pump, limits, capability-checked host functions, crash window.
 * `:app` constructs one per process and bridges it to `:extensions` through that module's
 * `LogicHost` port; nothing here runs on the main thread.
 *
 * Capability enforcement here is implemented by easyIDE over Chicory's interpreter; it is not
 * an audited isolation boundary (R-SEC-02).
 */
class WasmHost(
    private val loader: WasmModuleLoader,
    private val ports: HostPorts,
    private val settings: SettingsLookup,
    private val crashes: CrashPolicy,
    /** Application scope; event drains and memory-pressure drops run in it. */
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
    watchdogTickMs: Long = WasmPolicy.WATCHDOG_TICK_MS,
) : AutoCloseable {

    private class Live(
        val generation: Long,
        val instance: WasmInstance,
        val session: InstanceSession,
        val limits: WasmLimits,
        val events: EventQueue,
    ) {
        val draining = AtomicBoolean(false)
    }

    private class Slot {
        val mutex = Mutex()
        val state = MutableStateFlow<InstanceState>(InstanceState.Unloaded)
        val live = AtomicReference<Live?>(null)
        @Volatile var ext: WasmExtension? = null

        /** Non-null while activated: a command or request may re-instantiate after a discard. */
        @Volatile var context: ActivationContext? = null
        @Volatile var crashWindow: CrashWindow? = null
    }

    private val slots = ConcurrentHashMap<String, Slot>()
    private val workers: MutableSet<Thread> = ConcurrentHashMap.newKeySet()
    private val watchdog = Watchdog(watchdogTickMs)
    private val router = HostCallRouter(ports)
    private val generations = AtomicLong(0)
    private val messageIds = AtomicLong(0)
    private val providerList = MutableStateFlow<List<ProviderRegistration>>(emptyList())

    /** Providers registered by active WASM extensions, for the completion/hover mergers. */
    val providers: StateFlow<List<ProviderRegistration>> = providerList.asStateFlow()

    fun state(extensionId: String): StateFlow<InstanceState> = slot(extensionId).state.asStateFlow()

    /**
     * Instantiates [ext] and runs `ext_activate`. Idempotent for the same extension value;
     * a different version replaces the running instance.
     */
    suspend fun activate(ext: WasmExtension, context: ActivationContext): HostResult {
        if (!WasmLimits.enabled(settings)) {
            return HostResult.Err(ErrorCode.E_UNAVAILABLE, "WASM extensions are turned off ($WASM_ENABLED_KEY)")
        }
        val slot = slot(ext.id)
        return slot.mutex.withLock {
            if (slot.live.get() != null && slot.ext == ext) return@withLock HostResult.Ok(null)
            dropLocked(slot, sendDeactivate = true)
            // A new version, or the user re-enabling after a crash loop, starts a fresh window.
            if (slot.ext != ext || slot.state.value is InstanceState.Disabled) slot.crashWindow = null
            slot.ext = ext
            slot.context = context
            removeProviders(ext.id)
            instantiateLocked(slot, ext, context)
        }
    }

    /** Sends `deactivate`, waits at most `deactivateTimeoutMs`, then drops the instance for good. */
    suspend fun deactivate(extensionId: String) {
        val slot = slots[extensionId] ?: return
        slot.mutex.withLock {
            slot.context = null
            dropLocked(slot, sendDeactivate = true)
            unregisterAll(extensionId)
            slot.state.value = InstanceState.Unloaded
        }
    }

    suspend fun executeCommand(extensionId: String, command: String, args: JsonArray, context: JsonObject): HostResult {
        val (slot, live) = when (val t = liveOrActivate(extensionId)) {
            is Target.Ready -> t
            is Target.Unavailable -> return t.err
        }
        reentry(live)?.let { return it }
        val id = messageIds.incrementAndGet()
        return call(slot, live, "command $command", Messages.command(id, command, args, context), id)
    }

    /** `provider.<kind>` request to an extension that registered that kind (LSP result shapes back). */
    suspend fun providerRequest(extensionId: String, method: String, params: JsonObject): HostResult {
        val kind = method.removePrefix(PROVIDER_PREFIX)
        if (providerList.value.none { it.extensionId == extensionId && it.kind == kind }) {
            return HostResult.Err(ErrorCode.E_NOT_FOUND, "$extensionId has no $kind provider")
        }
        val (slot, live) = when (val t = liveOrActivate(extensionId)) {
            is Target.Ready -> t
            is Target.Unavailable -> return t.err
        }
        reentry(live)?.let { return it }
        val id = messageIds.incrementAndGet()
        return call(slot, live, "request $method", Messages.request(id, method, params), id)
    }

    /**
     * Queues an event for the extension if it subscribed (or, for `fs.changed`, watched).
     * Non-blocking; dropped when no instance is live (events never activate an extension).
     * Events carrying a `path` the extension may not read are withheld (same fs rule).
     */
    fun postEvent(extensionId: String, event: String, data: JsonObject) {
        val slot = slots[extensionId] ?: return
        val live = slot.live.get() ?: return
        if (event != FS_CHANGED && event !in live.session.subscriptions) return
        val path = (data["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (path != null) {
            val normalized = GuestPaths.normalize(path) ?: return
            if (GuestPaths.missingFor(live.session.ext.capabilities, normalized, write = false) != null) return
        } else if (event == FS_CHANGED && !live.session.ext.capabilities.has(Cap.FS_READ)) {
            return
        }
        enqueue(slot, live, event, data)
    }

    /** Memory pressure (arch.md 7.7 step 3): drop idle instances and parsed modules; commands re-instantiate. */
    fun trimMemory() {
        loader.trimMemory()
        for (slot in slots.values) {
            val live = slot.live.get() ?: continue
            if (live.instance.isRunning) continue
            scope.launch {
                slot.mutex.withLock {
                    if (slot.live.get() === live) {
                        dropLocked(slot, sendDeactivate = true)
                        slot.state.value = InstanceState.Unloaded
                    }
                }
            }
        }
    }

    override fun close() {
        for ((id, slot) in slots) {
            slot.live.getAndSet(null)?.let { closeLive(id, it) }
        }
        watchdog.close()
    }

    private fun slot(id: String): Slot = slots.getOrPut(id) { Slot() }

    private suspend fun instantiateLocked(slot: Slot, ext: WasmExtension, context: ActivationContext): HostResult {
        slot.state.value = InstanceState.Loading
        val limits = WasmLimits.resolve(settings, ext.manifestMemoryMb)
        val window = slot.crashWindow ?: CrashWindow(limits.maxCrashes, limits.crashWindowMs).also { slot.crashWindow = it }
        // Loading reads files and instantiating runs Chicory's parser/linker on untrusted bytes.
        val live = try {
            withContext(Dispatchers.IO) { build(slot, ext, limits) }
        } catch (e: ModuleRejectedException) {
            return failed(slot, ext, "load refused: ${e.message}")
        } catch (e: IOException) {
            return failed(slot, ext, "cannot read module: ${e.message}")
        } catch (e: RuntimeException) {
            return failed(slot, ext, "instantiation failed: ${e.message}")
        }
        slot.state.value = InstanceState.Activating
        return try {
            val version = live.instance.abiVersion()
            if (version != WasmPolicy.ABI_VERSION) {
                closeLive(ext.id, live)
                return failed(slot, ext, "ext_abi_version returned $version, expected ${WasmPolicy.ABI_VERSION}")
            }
            val reply = live.instance.activate(context.message(ext))
            val result = Messages.decodeReply(reply, null).getOrElse {
                throw GuestFailure(ErrorCode.E_INTERNAL, "malformed ext_activate reply: ${it.message}", true)
            }
            when (result) {
                is HostResult.Ok -> {
                    slot.live.set(live)
                    slot.state.value = InstanceState.Active
                    result
                }
                is HostResult.Err -> {
                    closeLive(ext.id, live)
                    failed(slot, ext, "ext_activate answered ${result.code}: ${result.message}")
                    result
                }
            }
        } catch (f: GuestFailure) {
            onGuestFailure(slot, live, "ext_activate", f, window)
            HostResult.Err(f.code, f.message ?: f.code.name)
        }
    }

    private fun build(slot: Slot, ext: WasmExtension, limits: WasmLimits): Live {
        val loaded = loader.load(ext.moduleFile, ext.moduleSha256, limits)
        val generation = generations.incrementAndGet()
        val session = InstanceSession(
            ext, limits,
            deliver = { event, data ->
                slot.live.get()?.takeIf { it.generation == generation }?.let { enqueue(slot, it, event, data) }
            },
            registerProvider = ::addProvider,
        )
        val instance = WasmInstance(
            loaded, limits, session, router, watchdog, nanoTime,
            onBusy = { busy ->
                slot.state.update { s ->
                    if (s is InstanceState.Active || s is InstanceState.Busy) {
                        if (busy) InstanceState.Busy else InstanceState.Active
                    } else {
                        s
                    }
                }
            },
            workers = workers,
        )
        return Live(generation, instance, session, limits, EventQueue(WasmPolicy.EVENT_QUEUE_CAPACITY))
    }

    private sealed interface Target {
        data class Ready(val slot: Slot, val live: Live) : Target
        data class Unavailable(val err: HostResult.Err) : Target
    }

    /**
     * The live instance, re-instantiated if a trap discarded it while the extension stays
     * activated. A load or activation failure is deterministic, so it is not retried per call.
     */
    private suspend fun liveOrActivate(extensionId: String): Target {
        val slot = slots[extensionId] ?: return notActive(extensionId)
        slot.live.get()?.let { return Target.Ready(slot, it) }
        return slot.mutex.withLock {
            slot.live.get()?.let { return@withLock Target.Ready(slot, it) }
            val ext = slot.ext
            val context = slot.context
            when (val st = slot.state.value) {
                is InstanceState.Disabled -> return@withLock Target.Unavailable(HostResult.Err(ErrorCode.E_UNAVAILABLE, st.reason))
                is InstanceState.Failed -> return@withLock Target.Unavailable(HostResult.Err(ErrorCode.E_UNAVAILABLE, st.reason))
                else -> Unit
            }
            if (ext == null || context == null) return@withLock notActive(extensionId)
            when (val r = instantiateLocked(slot, ext, context)) {
                is HostResult.Ok -> slot.live.get()?.let { Target.Ready(slot, it) } ?: notActive(extensionId)
                is HostResult.Err -> Target.Unavailable(r)
            }
        }
    }

    private fun notActive(id: String) = Target.Unavailable(HostResult.Err(ErrorCode.E_NOT_FOUND, "$id is not activated"))

    /** A call from inside any guest into a busy instance could wait on itself: refuse, never queue. */
    private fun reentry(live: Live): HostResult.Err? =
        if (Thread.currentThread() in workers && live.instance.isRunning) {
            HostResult.Err(ErrorCode.E_UNAVAILABLE, "${live.session.ext.id} is busy; re-entrant calls are refused")
        } else {
            null
        }

    private suspend fun call(slot: Slot, live: Live, entry: String, msg: JsonObject, id: Long): HostResult {
        val reply = try {
            live.instance.handle(msg, live.limits.callTimeoutMs)
        } catch (f: GuestFailure) {
            onGuestFailure(slot, live, entry, f, slot.crashWindow)
            return HostResult.Err(f.code, f.message ?: f.code.name)
        } ?: return HostResult.Ok(null)
        return Messages.decodeReply(reply, id).getOrElse { err ->
            val f = GuestFailure(ErrorCode.E_INTERNAL, "malformed reply to $entry: ${err.message}", true)
            onGuestFailure(slot, live, entry, f, slot.crashWindow)
            HostResult.Err(f.code, f.message ?: f.code.name)
        }
    }

    private fun enqueue(slot: Slot, live: Live, event: String, data: JsonObject) {
        live.events.offer(event, data)
        if (live.draining.compareAndSet(false, true)) scope.launch { drain(slot, live) }
    }

    /** Single drainer per instance; ordered delivery through the worker. */
    private suspend fun drain(slot: Slot, live: Live) {
        while (true) {
            val msg = live.events.poll()
            if (msg == null) {
                live.draining.set(false)
                // An offer between poll and the reset would otherwise wait for the next event.
                if (live.events.isEmpty() || !live.draining.compareAndSet(false, true)) return
                continue
            }
            if (slot.live.get() !== live) return
            try {
                live.instance.handle(msg, live.limits.callTimeoutMs)
            } catch (f: GuestFailure) {
                onGuestFailure(slot, live, "event", f, slot.crashWindow)
                return
            }
        }
    }

    private suspend fun dropLocked(slot: Slot, sendDeactivate: Boolean) {
        val live = slot.live.getAndSet(null) ?: return
        val id = live.session.ext.id
        if (sendDeactivate) {
            slot.state.value = InstanceState.Deactivating
            val answered = withTimeoutOrNull(live.limits.deactivateTimeoutMs) {
                runCatchingGuest { live.instance.handle(Messages.deactivate(), live.limits.deactivateTimeoutMs) }
            }
            if (answered == null) ports.log.write(id, LogLevel.WARN, "deactivate not answered cleanly within ${live.limits.deactivateTimeoutMs} ms; dropped")
        }
        closeLive(id, live)
    }

    /** A failing deactivate still ends in a drop; only its log line differs. */
    private suspend fun runCatchingGuest(block: suspend () -> Unit): Unit? = try {
        block()
    } catch (f: GuestFailure) {
        null
    }

    private fun closeLive(id: String, live: Live) {
        live.instance.close()
        live.events.clear()
        ports.files.unwatchAll(id)
    }

    private fun onGuestFailure(slot: Slot, live: Live, entry: String, f: GuestFailure, window: CrashWindow?) {
        val ext = live.session.ext
        ports.log.write(
            ext.id, LogLevel.ERROR,
            "${ext.id}@${ext.version} $entry failed ${f.code}: ${f.message}; fuel used ${live.instance.fuelUsed}, " +
                "guest time ${live.instance.guestMillis} ms, last fn ${live.session.lastFn ?: "-"}",
        )
        slot.live.compareAndSet(live, null)
        closeLive(ext.id, live)
        if (f.counted && window?.record(clock()) == true) {
            val reason = "Disabled after repeated crashes - re-enable in Extensions"
            slot.context = null
            unregisterAll(ext.id)
            slot.state.value = InstanceState.Disabled(reason)
            crashes.disableForCrashLoop(ext.id, reason)
        } else if (slot.state.value !is InstanceState.Loading) {
            slot.state.value = InstanceState.Unloaded
        }
    }

    private fun failed(slot: Slot, ext: WasmExtension, reason: String): HostResult.Err {
        ports.log.write(ext.id, LogLevel.ERROR, "${ext.id}@${ext.version}: $reason")
        slot.state.value = InstanceState.Failed(reason)
        return HostResult.Err(ErrorCode.E_UNAVAILABLE, reason)
    }

    private fun unregisterAll(extensionId: String) {
        removeProviders(extensionId)
        ports.commands.unregisterAll(extensionId)
    }

    private fun addProvider(p: ProviderRegistration) {
        providerList.update { list -> list.filterNot { it.extensionId == p.extensionId && it.kind == p.kind } + p }
    }

    private fun removeProviders(extensionId: String) {
        providerList.update { list -> list.filterNot { it.extensionId == extensionId } }
    }

    private companion object {
        const val PROVIDER_PREFIX = "provider."
        const val FS_CHANGED = "fs.changed"
    }
}
