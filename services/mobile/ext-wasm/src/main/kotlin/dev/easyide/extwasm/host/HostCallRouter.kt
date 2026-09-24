package dev.easyide.extwasm.host

import dev.easyide.extwasm.ErrorCode
import dev.easyide.extwasm.HostCallException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Result of one dispatched `host_call`, before it is encoded into guest memory. */
internal sealed interface Dispatch {
    data class Ok(val result: JsonElement?) : Dispatch
    data class Err(val code: ErrorCode, val message: String) : Dispatch
}

/**
 * Looks up `fn`, checks its capability rule, runs the handler and maps every failure to an
 * ABI error code (wasm-host.md sec 9.2 and 10). A denial or port failure answers the guest
 * with `ok:false` and never traps the instance: a guest may probe and degrade.
 */
internal class HostCallRouter(
    private val ports: HostPorts,
    private val table: Map<String, HostFn> = HostFunctionTable.rows,
) {

    suspend fun dispatch(session: InstanceSession, fn: String, args: JsonObject): Dispatch {
        session.lastFn = fn
        val row = table[fn] ?: return Dispatch.Err(ErrorCode.E_NOT_FOUND, "unknown host function $fn")
        val scope = HostCallScope(session, ports)
        // Ports are a real boundary (I/O, UI, process spawn): their failures become codes.
        return try {
            row.rule.denial(scope, args)?.let { reason -> return deny(session, fn, reason) }
            if (row.mutating) ports.log.write(session.ext.id, LogLevel.INFO, "$fn${describe(args)}")
            Dispatch.Ok(row.handler(scope, args))
        } catch (e: HostCallException) {
            Dispatch.Err(e.code, e.message ?: e.code.name)
        } catch (e: CancellationException) {
            Dispatch.Err(ErrorCode.E_CANCELLED, "host function $fn was cancelled")
        } catch (e: Exception) {
            ports.log.write(session.ext.id, LogLevel.ERROR, "$fn failed in the host: ${e.javaClass.simpleName}: ${e.message}")
            Dispatch.Err(ErrorCode.E_INTERNAL, "$fn failed in the host")
        }
    }

    private fun deny(session: InstanceSession, fn: String, reason: String): Dispatch {
        // One line per (instance, fn): a probing guest must not flood the Extension Log.
        if (session.deniedLogged.add(fn)) ports.log.write(session.ext.id, LogLevel.WARN, "denied $fn: $reason")
        return Dispatch.Err(ErrorCode.E_CAPABILITY, reason)
    }

    /** Short, non-content summary for the audit line: paths and ids, never file text. */
    private fun describe(args: JsonObject): String {
        val keys = listOf("path", "from", "to", "key", "url", "command")
        val parts = keys.mapNotNull { k -> (args[k] as? JsonPrimitive)?.takeIf { it.isString }?.let { "$k=${it.content}" } }
        return if (parts.isEmpty()) "" else parts.joinToString(" ", " ")
    }
}
