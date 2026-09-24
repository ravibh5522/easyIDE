package dev.easyide.extwasm.runtime

import dev.easyide.extwasm.ErrorCode
import dev.easyide.extwasm.HostResult
import dev.easyide.extwasm.WasmPolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * ABI v1 message shapes (sdk-reference "WASM host API"). Builders for what the host sends,
 * decoders for what the guest sends; decoders take already bounds-checked, parsed JSON.
 */
internal object Messages {
    private const val V = "v"

    /** A guest -> host `host_call` request. [id] is echoed back verbatim (any JSON value). */
    class Request(val id: JsonElement, val fn: String, val args: JsonObject)

    /** @return the request, or an error message suitable for an E_ARGS reply. */
    fun decodeRequest(msg: JsonObject): Result<Request> {
        val id = msg["id"] ?: JsonNull
        val fn = (msg["fn"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return Result.failure(IllegalArgumentException("request needs a string \"fn\""))
        val args = when (val a = msg["args"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> a
            else -> return Result.failure(IllegalArgumentException("\"args\" must be an object"))
        }
        return Result.success(Request(id, fn, args))
    }

    /** The id of a request that failed to decode, so the error reply can still carry it. */
    fun idOf(msg: JsonObject?): JsonElement = msg?.get("id") ?: JsonNull

    fun ok(id: JsonElement, result: JsonElement?): JsonObject = buildJsonObject {
        put(V, WasmPolicy.ABI_VERSION); put("id", id); put("ok", true)
        put("result", result ?: JsonNull)
    }

    fun error(id: JsonElement, code: ErrorCode, message: String): JsonObject = buildJsonObject {
        put(V, WasmPolicy.ABI_VERSION); put("id", id); put("ok", false)
        put("error", buildJsonObject { put("code", code.name); put("message", message) })
    }

    fun event(event: String, data: JsonObject, dropped: Int): JsonObject = buildJsonObject {
        put(V, WasmPolicy.ABI_VERSION); put("type", "event"); put("event", event); put("data", data)
        if (dropped > 0) put("events.dropped", dropped)
    }

    fun command(id: Long, command: String, args: JsonArray, context: JsonObject): JsonObject = buildJsonObject {
        put(V, WasmPolicy.ABI_VERSION); put("type", "command"); put("id", id)
        put("command", command); put("args", args); put("context", context)
    }

    fun request(id: Long, method: String, params: JsonObject): JsonObject = buildJsonObject {
        put(V, WasmPolicy.ABI_VERSION); put("type", "request"); put("id", id)
        put("method", method); put("params", params)
    }

    fun deactivate(): JsonObject = buildJsonObject {
        put(V, WasmPolicy.ABI_VERSION); put("type", "deactivate")
    }

    /**
     * Decodes a guest reply to a command/request/activation. [expectedId] null means the
     * message had no id (activation). A reply for another id is malformed: the guest is out of
     * step with the host and the instance cannot be trusted further.
     */
    fun decodeReply(reply: JsonObject, expectedId: Long?): Result<HostResult> {
        if (expectedId != null) {
            val id = (reply["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            if (id != expectedId) return Result.failure(IllegalStateException("reply id $id does not match request $expectedId"))
        }
        val ok = (reply["ok"] as? JsonPrimitive)?.booleanOrNull
            ?: return Result.failure(IllegalStateException("reply needs a boolean \"ok\""))
        if (ok) return Result.success(HostResult.Ok(reply["result"]))
        val err = reply["error"] as? JsonObject
        val code = ErrorCode.parse((err?.get("code") as? JsonPrimitive)?.contentOrNull)
        val message = (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: ""
        return Result.success(HostResult.Err(code, message))
    }
}
