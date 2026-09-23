package dev.easyide.extensions.host

import dev.easyide.extensions.action.ExtensionLog
import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.action.LogLevel
import dev.easyide.extensions.capability.CapabilitySet
import dev.easyide.extensions.manifest.ActivationEvent
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extensions.settings.ExtensionSettings
import dev.easyide.extensions.settings.ExtensionSettings.int
import dev.easyide.extensions.settings.SettingsPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

enum class ActivationState { DISABLED, INACTIVE, ACTIVATING, ACTIVE, CRASHED, FAILED, DEACTIVATING, CRASH_DISABLED }

sealed interface ActivationResult {
    data object Ok : ActivationResult
    /** Bad module, ABI mismatch, refused start: not crash-counted, needs an explicit retry. */
    data class Failed(val message: String) : ActivationResult
}

/**
 * Something that makes an enabled extension live beyond its declarative contributions: the
 * WASM host (instantiate, `ext_activate`) or the language-server supervisor (servers become
 * eligible). Declarative contributions never wait for activation (sdk-reference).
 */
interface Activator {
    fun handles(d: ExtensionDescriptor): Boolean
    suspend fun activate(d: ExtensionDescriptor, granted: CapabilitySet): ActivationResult
    suspend fun deactivate(d: ExtensionDescriptor)
}

fun interface Clock {
    fun nowMs(): Long
}

/**
 * Per-extension activation state machine (extension-runtime.md sec 5.2). One activation in
 * flight per extension: concurrent events join it through a per-id mutex. Crashes inside an
 * active extension are reported by activators via [reportCrash]; `extensions.wasm.maxCrashes`
 * within `crashWindowSec` crash-disables it (persisted) until the user re-enables it.
 */
class ActivationManager(
    private val activators: List<Activator>,
    private val journal: CrashJournal,
    private val inventory: ExtensionInventory,
    private val settings: SettingsPort,
    private val clock: Clock,
    private val log: ExtensionLog,
) {
    private val state = MutableStateFlow<Map<ExtensionId, ActivationState>>(emptyMap())
    val states: StateFlow<Map<ExtensionId, ActivationState>> = state.asStateFlow()

    private val locks = ConcurrentHashMap<ExtensionId, Mutex>()
    private val crashes = ConcurrentHashMap<ExtensionId, MutableList<Long>>()
    @Volatile private var enabled: Map<ExtensionId, EnabledExtension> = emptyMap()

    /**
     * Applies a new enabled set: newcomers become INACTIVE (ACTIVE at once when nothing needs
     * activating), extensions that left are deactivated and become DISABLED.
     */
    suspend fun onEnabledSetChanged(set: EnabledSet, crashDisabled: Set<ExtensionId> = emptySet()) {
        val next = set.extensions.associateBy { it.id }
        val removed = enabled.filterKeys { it !in next }
        enabled = next
        for ((id, ext) in removed) lock(id).withLock {
            if (stateOf(id) == ActivationState.ACTIVE) deactivate(ext)
            setState(id, ActivationState.DISABLED)
        }
        for ((id, ext) in next) {
            val current = stateOf(id)
            if (current == null || current == ActivationState.DISABLED) {
                setState(id, if (activatorsFor(ext.descriptor).isEmpty()) ActivationState.ACTIVE else ActivationState.INACTIVE)
            }
        }
        crashDisabled.forEach { setState(it, ActivationState.CRASH_DISABLED) }
    }

    /** Activates every enabled extension whose `activationEvents` include [event]. */
    suspend fun onEvent(event: ActivationEvent) {
        for (ext in enabled.values) {
            if (event in ext.descriptor.activationEvents) activate(ext, retrying = false)
        }
    }

    /** The `onCommand` path: activate [id] if needed (bounded by `activateTimeoutMs`) and report the outcome. */
    suspend fun ensureActive(id: ExtensionId): ActivationState {
        val ext = enabled[id] ?: return stateOf(id) ?: ActivationState.DISABLED
        activate(ext, retrying = false)
        return stateOf(id) ?: ActivationState.DISABLED
    }

    /** User retry of a FAILED activation. */
    suspend fun retry(id: ExtensionId) {
        enabled[id]?.let { activate(it, retrying = true) }
    }

    /**
     * An active extension crashed (trap, fuel exhaustion, server death counted by its
     * activator). The instance is gone; the next event re-activates it unless the crash
     * budget is spent, which crash-disables it persistently.
     */
    suspend fun reportCrash(id: ExtensionId, message: String) {
        val ext = enabled[id] ?: return
        lock(id).withLock {
            val now = clock.nowMs()
            val windowMs = settings.int(ExtensionSettings.WASM_CRASH_WINDOW_SEC) * MS_PER_SEC
            val recent = crashes.getOrPut(id) { ArrayList() }.apply { add(now); removeAll { now - it > windowMs } }
            log.append(LogEntry(id, LogLevel.ERROR, "crashed: $message"))
            if (recent.size >= settings.int(ExtensionSettings.WASM_MAX_CRASHES)) {
                deactivate(ext)
                setState(id, ActivationState.CRASH_DISABLED)
                inventory.setCrashDisabled(id, true)
                log.append(LogEntry(id, LogLevel.ERROR, "disabled after ${recent.size} crashes"))
            } else {
                setState(id, ActivationState.CRASHED)
            }
        }
    }

    /** The user re-enabled a crash-disabled extension. */
    suspend fun clearCrashDisable(id: ExtensionId) {
        crashes.remove(id)
        inventory.setCrashDisabled(id, false)
        if (stateOf(id) == ActivationState.CRASH_DISABLED) setState(id, ActivationState.INACTIVE)
    }

    private suspend fun activate(ext: EnabledExtension, retrying: Boolean) = lock(ext.id).withLock {
        val current = stateOf(ext.id)
        val eligible = current == ActivationState.INACTIVE || current == ActivationState.CRASHED ||
            (retrying && current == ActivationState.FAILED)
        if (!eligible) return@withLock
        setState(ext.id, ActivationState.ACTIVATING)
        val timeoutMs = settings.int(ExtensionSettings.WASM_ACTIVATE_TIMEOUT_MS).toLong()
        journal.begin(ext.id, CrashJournal.Phase.ACTIVATE)
        val failure = try {
            activatorsFor(ext.descriptor).firstNotNullOfOrNull { a ->
                when (val r = withTimeoutOrNull(timeoutMs) { a.activate(ext.descriptor, ext.granted) }) {
                    null -> "activation timed out after ${timeoutMs}ms"
                    is ActivationResult.Failed -> r.message
                    ActivationResult.Ok -> null
                }
            }
        } finally {
            journal.end(ext.id)
        }
        if (failure == null) {
            setState(ext.id, ActivationState.ACTIVE)
        } else {
            log.append(LogEntry(ext.id, LogLevel.ERROR, "activation failed: $failure"))
            // Undo partial activation so a retry starts clean.
            deactivate(ext)
            setState(ext.id, ActivationState.FAILED)
        }
    }

    private suspend fun deactivate(ext: EnabledExtension) {
        setState(ext.id, ActivationState.DEACTIVATING)
        val timeoutMs = settings.int(ExtensionSettings.WASM_DEACTIVATE_TIMEOUT_MS).toLong()
        for (a in activatorsFor(ext.descriptor)) {
            if (withTimeoutOrNull(timeoutMs) { a.deactivate(ext.descriptor) } == null) {
                log.append(LogEntry(ext.id, LogLevel.WARN, "deactivation timed out after ${timeoutMs}ms"))
            }
        }
        setState(ext.id, ActivationState.INACTIVE)
    }

    private fun activatorsFor(d: ExtensionDescriptor) = activators.filter { it.handles(d) }
    private fun lock(id: ExtensionId) = locks.getOrPut(id) { Mutex() }
    private fun stateOf(id: ExtensionId) = state.value[id]
    private fun setState(id: ExtensionId, s: ActivationState) = state.update { it + (id to s) }

    private companion object { const val MS_PER_SEC = 1000L }
}
