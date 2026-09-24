package dev.easyide.extwasm.host

import dev.easyide.extwasm.ErrorCode
import dev.easyide.extwasm.HostCallException
import dev.easyide.extwasm.WasmLimits
import dev.easyide.extwasm.WasmPolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What the host knows about one L2 extension, from its manifest and approval record
 * (`:extensions` builds it). The declared sets bound what `commands.register`,
 * `providers.register`, `ui.setViewData` and `config.*` may name.
 */
data class WasmExtension(
    val id: String,
    val version: String,
    val moduleFile: File,
    /** sha256 recorded at install; checked again at every load. */
    val moduleSha256: String,
    /** `easyide.wasm.memoryMb`; can only lower `extensions.wasm.maxMemoryMb`. */
    val manifestMemoryMb: Int?,
    val capabilities: CapabilitySet,
    /** `contributes.commands` ids. */
    val commands: Set<String> = emptySet(),
    /** `easyide.wasm.providers` kinds. */
    val providerKinds: Set<String> = emptySet(),
    /** Contributed view ids (filled with `ui.setViewData` without `ui.stage`). */
    val views: Set<String> = emptySet(),
    /** Contributed stage ids (their content needs `ui.stage`). */
    val stages: Set<String> = emptySet(),
    /** Keys of `contributes.configuration`: the extension's own settings. */
    val settingKeys: Set<String> = emptySet(),
)

/** A `providers.register` call that succeeded. */
data class ProviderRegistration(val extensionId: String, val kind: String, val languages: List<String>)

/**
 * Per-instance state host functions read and write. Lives exactly as long as the instance.
 * Subscriptions are read by `postEvent` from any thread, so they are concurrent; the rest
 * is touched only on the worker thread.
 */
internal class InstanceSession(
    val ext: WasmExtension,
    val limits: WasmLimits,
    /** Enqueues an event for this instance regardless of subscriptions (async handle results). */
    val deliver: (event: String, data: JsonObject) -> Unit,
    /** Publishes a provider registration to the host-wide registry. */
    val registerProvider: (ProviderRegistration) -> Unit,
) {
    val subscriptions: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val deniedLogged: MutableSet<String> = HashSet()

    /** Last `fn` the guest called, for the failure log line. */
    @Volatile var lastFn: String? = null
    private val nextHandle = AtomicLong(1)
    private val pendingHandles = AtomicInteger(0)

    /** Allocates an async handle, or fails E_LIMIT at [WasmPolicy.MAX_PENDING_HANDLES]. */
    fun openHandle(): OpenHandle {
        if (pendingHandles.incrementAndGet() > WasmPolicy.MAX_PENDING_HANDLES) {
            pendingHandles.decrementAndGet()
            throw HostCallException(ErrorCode.E_LIMIT, "more than ${WasmPolicy.MAX_PENDING_HANDLES} pending handles")
        }
        return OpenHandle(nextHandle.getAndIncrement())
    }

    /** One async operation; its slot is freed by [HandleSink.finish] or [abandon]. */
    inner class OpenHandle(val id: Long) : HandleSink {
        private val finished = AtomicBoolean(false)

        override fun emit(event: String, data: JsonObject) {
            if (!finished.get()) deliver(event, withHandle(data, id))
        }

        override fun finish(event: String, data: JsonObject) {
            if (finished.compareAndSet(false, true)) {
                pendingHandles.decrementAndGet()
                deliver(event, withHandle(data, id))
            }
        }

        /** The operation never started (its port threw): free the slot silently. */
        fun abandon() {
            if (finished.compareAndSet(false, true)) pendingHandles.decrementAndGet()
        }
    }

    private fun withHandle(data: JsonObject, handle: Long) = JsonObject(data + ("handle" to JsonPrimitive(handle)))
}

/** Receiver of every host function handler: the calling extension, its limits and the ports. */
class HostCallScope internal constructor(
    internal val session: InstanceSession,
    val ports: HostPorts,
) {
    val ext: WasmExtension get() = session.ext
    val limits: WasmLimits get() = session.limits
}

internal fun badArgs(message: String): Nothing = throw HostCallException(ErrorCode.E_ARGS, message)

internal fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: badArgs("\"$key\" must be a string")

internal fun JsonObject.optStr(key: String): String? = when (val v = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> if (v.isString) v.content else badArgs("\"$key\" must be a string")
    else -> badArgs("\"$key\" must be a string")
}

internal fun JsonObject.obj(key: String): JsonObject = when (val v = this[key]) {
    null, JsonNull -> JsonObject(emptyMap())
    is JsonObject -> v
    else -> badArgs("\"$key\" must be an object")
}

internal fun JsonObject.arr(key: String): JsonArray = when (val v = this[key]) {
    null, JsonNull -> JsonArray(emptyList())
    is JsonArray -> v
    else -> badArgs("\"$key\" must be an array")
}

internal fun JsonObject.long(key: String): Long =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: badArgs("\"$key\" must be an integer")

internal fun JsonArray.strings(what: String): List<String> = map {
    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull ?: badArgs("$what must be strings")
}
