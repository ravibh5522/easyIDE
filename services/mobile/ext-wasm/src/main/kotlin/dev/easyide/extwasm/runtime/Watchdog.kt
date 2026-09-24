package dev.easyide.extwasm.runtime

import dev.easyide.extwasm.WasmPolicy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Why the watchdog stopped a guest call. */
internal enum class Interrupt { TIMEOUT, CANCELLED }

/**
 * Timing and interruption state of the one top-level guest call an instance is running.
 * Written by the worker thread (start, host-call enter/exit) and read by the watchdog.
 *
 * Only guest time counts against the timeout: time spent inside a host function (a quick
 * pick waiting for the user, a port doing I/O) is excluded (wasm-host.md sec 7).
 */
internal class CallBudget(private val nanoTime: () -> Long) {
    @Volatile var running = false
        private set
    @Volatile var interrupt: Interrupt? = null
        private set
    @Volatile private var timeoutNanos = 0L
    @Volatile private var startedAt = 0L
    @Volatile private var hostEnteredAt = 0L
    @Volatile private var hostNanos = 0L
    @Volatile var inHostCall = false
        private set

    /** Host calls made during the current top-level call (worker thread only). */
    var hostCalls = 0

    /** Set when a call went over `maxHostCallsPerCall`; the instance is discarded at call end. */
    var hostCallLimitHit = false

    fun start(timeoutMs: Long) {
        interrupt = null
        hostNanos = 0
        hostCalls = 0
        hostCallLimitHit = false
        inHostCall = false
        timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        startedAt = nanoTime()
        running = true
    }

    fun finish() {
        running = false
    }

    fun enterHost() {
        hostEnteredAt = nanoTime()
        inHostCall = true
    }

    fun exitHost() {
        hostNanos += nanoTime() - hostEnteredAt
        inHostCall = false
    }

    fun guestNanos(): Long {
        val now = nanoTime()
        val inHost = if (inHostCall) now - hostEnteredAt else 0L
        return now - startedAt - hostNanos - inHost
    }

    /** Called by the watchdog; the first reason wins. */
    fun requestInterrupt(reason: Interrupt) {
        if (interrupt == null) interrupt = reason
    }

    fun overTime(): Boolean = running && !inHostCall && guestNanos() > timeoutNanos
}

/** What the watchdog needs from an instance: its budget and the fuel global to poison. */
internal interface Watched {
    val budget: CallBudget
    fun poisonFuel()
}

/**
 * One shared timer (wasm-host.md sec 8) that checks every running call each
 * [WasmPolicy.WATCHDOG_TICK_MS]. It interrupts by writing -1 into the fuel global, so the
 * next metered checkpoint traps; it rewrites on every tick while the call is still running,
 * because the guest's own read-modify-write of the global can overwrite a single store
 * (observed in the 2026-09-24 spike). Chicory's `Thread.interrupt` polling is not used: it
 * misses `br_if` loops, and the fuel channel already covers every loop and function entry.
 */
internal class Watchdog(tickMs: Long = WasmPolicy.WATCHDOG_TICK_MS) : AutoCloseable {
    private val watched = ConcurrentHashMap.newKeySet<Watched>()
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "wasm-watchdog").apply { isDaemon = true }
    }
    private val task: ScheduledFuture<*> = timer.scheduleWithFixedDelay(::tick, tickMs, tickMs, TimeUnit.MILLISECONDS)

    fun add(w: Watched) {
        watched += w
    }

    fun remove(w: Watched) {
        watched -= w
    }

    private fun tick() {
        for (w in watched) {
            val b = w.budget
            if (!b.running) continue
            if (b.overTime()) b.requestInterrupt(Interrupt.TIMEOUT)
            if (b.interrupt != null) w.poisonFuel()
        }
    }

    override fun close() {
        task.cancel(false)
        timer.shutdownNow()
    }
}
