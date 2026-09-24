package dev.easyide.extwasm.runtime

import com.dylibso.chicory.runtime.GlobalInstance
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.wasm.types.MutabilityType
import com.dylibso.chicory.wasm.types.ValType
import com.dylibso.chicory.wasm.types.Value
import dev.easyide.extwasm.ErrorCode
import dev.easyide.extwasm.WasmLimits
import dev.easyide.extwasm.WasmPolicy
import dev.easyide.extwasm.host.Dispatch
import dev.easyide.extwasm.host.HostCallRouter
import dev.easyide.extwasm.host.InstanceSession
import dev.easyide.extwasm.load.AbiV1
import dev.easyide.extwasm.load.LoadedModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Why a guest call failed. [counted] failures feed the crash window (wasm-host.md sec 10);
 * every failure discards the instance.
 */
internal class GuestFailure(val code: ErrorCode, message: String, val counted: Boolean) : Exception(message)

/** Thrown out of `host_call` to unwind the guest once an interrupt is pending. */
private class GuestAbort : RuntimeException()

/**
 * Global whose value is volatile, so the watchdog's fuel write from another thread is
 * guaranteed visible to the interpreter (JMM), not merely observed to be (spike).
 */
private class VolatileGlobal(lo: Long, hi: Long, type: ValType, mut: MutabilityType) :
    GlobalInstance(lo, hi, type, mut) {
    @Volatile private var v = lo
    override fun getValue(): Long = v
    override fun getValueLow(): Long = v
    override fun setValue(value: Long) { v = value }
    override fun setValueLow(value: Long) { v = value }
    override fun setValue(value: Value) { v = value.raw() }
}

/**
 * One Chicory instance of one extension on its own worker thread (R-ENG-12): every guest
 * entry and every guest-memory access happens on [worker], so the instance needs no locks.
 * `host_call` runs host functions on that same thread via the router.
 */
internal class WasmInstance(
    loaded: LoadedModule,
    private val limits: WasmLimits,
    private val session: InstanceSession,
    private val router: HostCallRouter,
    private val watchdog: Watchdog,
    nanoTime: () -> Long,
    /** Called on the worker when a top-level call starts (true) and ends (false). */
    private val onBusy: (Boolean) -> Unit,
    /** Registry of all worker threads, so the host can tell a call from inside any guest. */
    private val workers: MutableSet<Thread>,
) : Watched, AutoCloseable {

    override val budget = CallBudget(nanoTime)

    @Volatile private var workerThread: Thread? = null
    @Volatile private var closed = false
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "wasm-${session.ext.id}", WasmPolicy.WORKER_STACK_BYTES).also {
            it.isDaemon = true
            workerThread = it
            workers += it
        }
    }
    private val dispatcher = worker.asCoroutineDispatcher()

    private val instance: Instance = Instance.builder(loaded.module)
        .withImportValues(ImportValues.builder().addFunction(hostCallImport()).build())
        .withMemoryLimits(loaded.memoryLimits)
        .withGlobalFactory { lo, hi, type, mut ->
            if (mut == MutabilityType.Var) VolatileGlobal(lo, hi, type, mut) else GlobalInstance(lo, hi, type, mut)
        }
        .withStart(false)
        .build()
    private val fuel: GlobalInstance = instance.global(loaded.fuelGlobalIndex)
    private val memory = GuestMemory(instance, limits.maxMessageBytes)
    private val handle = instance.export(AbiV1.HANDLE)
    private val activateFn = instance.export(AbiV1.ACTIVATE)
    private val abiVersionFn = instance.export(AbiV1.ABI_VERSION)
    private val initializeFn = if (exports(loaded, AbiV1.INITIALIZE)) instance.export(AbiV1.INITIALIZE) else null

    init {
        watchdog.add(this)
    }

    val isOnWorker: Boolean get() = Thread.currentThread() === workerThread

    override fun poisonFuel() = fuel.setValue(-1L)

    /** Requests the running call (if any) to stop with E_CANCELLED. */
    fun cancel() {
        if (budget.running) budget.requestInterrupt(Interrupt.CANCELLED)
    }

    /** Runs the optional `_initialize` export once, metered like activation; a no-op without one. */
    suspend fun initialize() {
        val init = initializeFn ?: return
        enter(limits.activateTimeoutMs) { init.apply() }
    }

    private fun exports(loaded: LoadedModule, name: String): Boolean {
        val section = loaded.module.exportSection()
        return (0 until section.exportCount()).any { section.getExport(it).name() == name }
    }

    suspend fun abiVersion(): Int = enter(limits.activateTimeoutMs) { abiVersionFn.apply()[0].toInt() }

    /** `ext_activate`: the reply is mandatory. */
    suspend fun activate(message: JsonObject): JsonObject = enter(limits.activateTimeoutMs) {
        val (p, n) = memory.writeInput(message.toString().toByteArray())
        val r = activateFn.apply(p.toLong(), n.toLong())[0].toInt()
        if (r == 0) throw AbiViolation("ext_activate returned no reply")
        memory.takePrefixed(r)
    }

    /** `ext_handle`: null when the guest answers 0 (no response). */
    suspend fun handle(message: JsonObject, timeoutMs: Long): JsonObject? = enter(timeoutMs) {
        val (p, n) = memory.writeInput(message.toString().toByteArray())
        val r = handle.apply(p.toLong(), n.toLong())[0].toInt()
        if (r == 0) null else memory.takePrefixed(r)
    }

    /**
     * Runs one top-level guest call on the worker with fresh fuel and a fresh budget, and maps
     * every way it can fail to a [GuestFailure]. This is the guest-entry boundary: traps,
     * Chicory errors and interpreter stack/heap exhaustion are caught here and only here.
     */
    private suspend fun <T> enter(timeoutMs: Long, block: () -> T): T {
        if (closed) throw dropped()
        return try {
            withContext(dispatcher) { onWorker(timeoutMs, block) }
        } catch (e: CancellationException) {
            // A closed worker rejects the task and kotlinx cancels only this withContext.
            if (closed && currentCoroutineContext().isActive) throw dropped() else throw e
        }
    }

    private fun dropped() = GuestFailure(ErrorCode.E_UNAVAILABLE, "the instance was dropped", counted = false)

    private fun <T> onWorker(timeoutMs: Long, block: () -> T): T {
        if (closed) throw dropped()
        fuel.setValue(limits.fuelPerCall)
        budget.start(timeoutMs)
        onBusy(true)
        val result = try {
            block()
        } catch (e: GuestAbort) {
            throw interrupted()
        } catch (e: AbiViolation) {
            throw budget.interrupt?.let { interrupted() } ?: GuestFailure(ErrorCode.E_INTERNAL, "malformed guest reply: ${e.message}", true)
        } catch (e: ChicoryException) {
            throw classifyTrap(e.message)
        } catch (e: RuntimeException) {
            // Anything else out of the interpreter is a runtime bug hit by guest code: it must
            // cost only this instance, never the app.
            throw budget.interrupt?.let { interrupted() } ?: GuestFailure(ErrorCode.E_INTERNAL, "runtime error: $e", true)
        } catch (e: StackOverflowError) {
            throw GuestFailure(ErrorCode.E_LIMIT, "guest exhausted the interpreter stack", true)
        } catch (e: OutOfMemoryError) {
            throw GuestFailure(ErrorCode.E_LIMIT, "guest exhausted the app heap", true)
        } finally {
            budget.finish()
            onBusy(false)
        }
        // A call that completed after an interrupt was requested may have left the fuel poisoned.
        budget.interrupt?.let { throw interrupted() }
        if (budget.hostCallLimitHit) {
            throw GuestFailure(ErrorCode.E_LIMIT, "more than ${limits.maxHostCallsPerCall} host calls in one call", true)
        }
        return result
    }

    private fun classifyTrap(message: String?): GuestFailure = when {
        budget.interrupt != null -> interrupted()
        fuel.value < 0 -> GuestFailure(ErrorCode.E_LIMIT, "fuel exhausted (extensions.wasm.fuelPerCall ${limits.fuelPerCall})", true)
        message?.contains(STACK_EXHAUSTED) == true -> GuestFailure(ErrorCode.E_LIMIT, "guest call stack exhausted", true)
        else -> GuestFailure(ErrorCode.E_INTERNAL, "trap: $message", true)
    }

    private fun interrupted(): GuestFailure = when (budget.interrupt) {
        Interrupt.CANCELLED -> GuestFailure(ErrorCode.E_CANCELLED, "call cancelled", false)
        else -> GuestFailure(ErrorCode.E_TIMEOUT, "guest time limit exceeded", true)
    }

    private fun hostCallImport() = HostFunction(AbiV1.HOST_MODULE, AbiV1.HOST_CALL, AbiV1.HOST_CALL_TYPE) { _, args ->
        if (budget.interrupt != null) throw GuestAbort()
        longArrayOf(hostCall(args[0].toInt(), args[1].toInt()).toLong())
    }

    /** One `host_call`: decode, dispatch, encode. Guest errors become ok:false replies. */
    private fun hostCall(ptr: Int, len: Int): Int {
        val reply = if (++budget.hostCalls > limits.maxHostCallsPerCall) {
            budget.hostCallLimitHit = true
            Messages.error(Messages.idOf(null), ErrorCode.E_LIMIT, "host call limit ${limits.maxHostCallsPerCall} reached")
        } else {
            answer(ptr, len)
        }
        var bytes = reply.toString().toByteArray()
        if (bytes.size > limits.maxMessageBytes) {
            bytes = Messages.error(reply["id"] ?: Messages.idOf(null), ErrorCode.E_LIMIT, "reply exceeds extensions.wasm.maxMessageKb")
                .toString().toByteArray()
        }
        return memory.writePrefixed(bytes)
    }

    private fun answer(ptr: Int, len: Int): JsonObject {
        val msg = try {
            memory.readRequest(ptr, len)
        } catch (e: AbiViolation) {
            return Messages.error(Messages.idOf(null), ErrorCode.E_ARGS, e.message ?: "bad request")
        }
        val req = Messages.decodeRequest(msg).getOrElse {
            return Messages.error(Messages.idOf(msg), ErrorCode.E_ARGS, it.message ?: "bad request")
        }
        budget.enterHost()
        val result = try {
            runBlocking { router.dispatch(session, req.fn, req.args) }
        } finally {
            budget.exitHost()
        }
        return when (result) {
            is Dispatch.Ok -> Messages.ok(req.id, result.result)
            is Dispatch.Err -> Messages.error(req.id, result.code, result.message)
        }
    }

    val isRunning: Boolean get() = budget.running

    /** Fuel consumed by the last (or current) call, for the failure log line. */
    val fuelUsed: Long get() = limits.fuelPerCall - maxOf(fuel.value, 0L)

    val guestMillis: Long get() = budget.guestNanos() / NANOS_PER_MS

    override fun close() {
        closed = true
        watchdog.remove(this)
        cancel()
        worker.shutdown()
        workerThread?.let { workers -= it }
    }

    private companion object {
        const val STACK_EXHAUSTED = "call stack exhausted"
        const val NANOS_PER_MS = 1_000_000L
    }
}
