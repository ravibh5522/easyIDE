package dev.easyide.lsp.jsonrpc

import dev.easyide.lsp.json.int
import dev.easyide.lsp.json.long
import dev.easyide.lsp.json.obj
import dev.easyide.lsp.json.putOpt
import dev.easyide.lsp.json.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A JSON-RPC id. Server ids are echoed verbatim, so both shapes must survive a round trip. */
sealed interface RpcId {
    data class Num(val value: Long) : RpcId
    data class Str(val value: String) : RpcId
}

sealed interface RpcMessage {
    data class Request(val id: RpcId, val method: String, val params: JsonElement?) : RpcMessage
    data class Notification(val method: String, val params: JsonElement?) : RpcMessage

    /**
     * Exactly one of [result] / [error] is meaningful; a success with a `null` result carries
     * [JsonNull], never Kotlin null, so "no result" and "result null" cannot be confused.
     */
    data class Response(val id: RpcId?, val result: JsonElement?, val error: ResponseError?) : RpcMessage
}

data class ResponseError(val code: Int, val message: String, val data: JsonElement? = null)

/** JSON-RPC 2.0 and LSP 3.17 reserved error codes. */
object ErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    const val SERVER_NOT_INITIALIZED = -32002
    const val REQUEST_FAILED = -32803
    const val SERVER_CANCELLED = -32802
    const val CONTENT_MODIFIED = -32801
    const val REQUEST_CANCELLED = -32800
}

/** Why a connection ended. Everything except [Requested] is a crash for the session. */
sealed interface CloseReason {
    data object Requested : CloseReason
    data object TransportClosed : CloseReason
    data class ProtocolError(val detail: String) : CloseReason
}

/** Malformed input from the peer. Closing the connection is the only recovery (no resync). */
class ProtocolException(message: String) : Exception(message)

/** Pending calls complete with this when the connection closes under them. */
class ConnectionClosedException(val reason: CloseReason) : Exception("connection closed: $reason")

/** Thrown by a server-to-client request handler to answer with a JSON-RPC error. */
class RpcErrorException(val error: ResponseError) : Exception(error.message)

/** Encoding and classification of JSON-RPC 2.0 messages. */
object RpcCodec {
    private const val JSONRPC = "jsonrpc"
    private const val VERSION = "2.0"
    private const val ID = "id"
    private const val METHOD = "method"
    private const val PARAMS = "params"
    private const val RESULT = "result"
    private const val ERROR = "error"
    private const val CODE = "code"
    private const val MESSAGE = "message"
    private const val DATA = "data"

    fun encode(message: RpcMessage): JsonObject = buildJsonObject {
        put(JSONRPC, JsonPrimitive(VERSION))
        when (message) {
            is RpcMessage.Request -> {
                put(ID, idJson(message.id))
                put(METHOD, JsonPrimitive(message.method))
                putOpt(PARAMS, message.params)
            }
            is RpcMessage.Notification -> {
                put(METHOD, JsonPrimitive(message.method))
                putOpt(PARAMS, message.params)
            }
            is RpcMessage.Response -> {
                put(ID, message.id?.let(::idJson) ?: JsonNull)
                val error = message.error
                if (error != null) {
                    put(ERROR, buildJsonObject {
                        put(CODE, JsonPrimitive(error.code))
                        put(MESSAGE, JsonPrimitive(error.message))
                        putOpt(DATA, error.data)
                    })
                } else {
                    put(RESULT, message.result ?: JsonNull)
                }
            }
        }
    }

    /**
     * Classifies a parsed object: `method` + `id` is a request, `method` alone a
     * notification, `id` (possibly null) with `result` or `error` a response.
     *
     * @throws ProtocolException for anything else, a missing `jsonrpc`, or bad field types.
     */
    fun decode(element: JsonElement): RpcMessage {
        val o = element.obj ?: throw ProtocolException("message is not a JSON object")
        if (o[JSONRPC].str != VERSION) throw ProtocolException("missing or wrong jsonrpc version")
        val params = o[PARAMS]?.also {
            if (it !is JsonObject && it !is JsonArray) throw ProtocolException("params must be object or array")
        }
        val methodElement = o[METHOD]
        if (methodElement != null) {
            val method = methodElement.str ?: throw ProtocolException("method is not a string")
            val idElement = o[ID]
            return if (idElement == null || idElement is JsonNull) {
                RpcMessage.Notification(method, params)
            } else {
                RpcMessage.Request(parseId(idElement), method, params)
            }
        }
        if (!o.containsKey(ID)) throw ProtocolException("message has neither method nor id")
        val idElement = o.getValue(ID)
        val id = if (idElement is JsonNull) null else parseId(idElement)
        val errorElement = o[ERROR]
        if (errorElement != null && errorElement !is JsonNull) {
            val e = errorElement.obj ?: throw ProtocolException("error is not an object")
            val code = e[CODE].int ?: throw ProtocolException("error.code is not an integer")
            return RpcMessage.Response(id, null, ResponseError(code, e[MESSAGE].str.orEmpty(), e[DATA]))
        }
        if (!o.containsKey(RESULT)) throw ProtocolException("response has neither result nor error")
        return RpcMessage.Response(id, o.getValue(RESULT), null)
    }

    private fun idJson(id: RpcId): JsonPrimitive = when (id) {
        is RpcId.Num -> JsonPrimitive(id.value)
        is RpcId.Str -> JsonPrimitive(id.value)
    }

    private fun parseId(element: JsonElement): RpcId {
        element.str?.let { return RpcId.Str(it) }
        element.long?.let { return RpcId.Num(it) }
        throw ProtocolException("id must be an integer or a string")
    }
}
