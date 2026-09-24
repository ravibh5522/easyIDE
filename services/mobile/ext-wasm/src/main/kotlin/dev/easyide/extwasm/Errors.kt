package dev.easyide.extwasm

import kotlinx.serialization.json.JsonElement

/** ABI v1 error codes, spelled exactly as they travel in `{"error":{"code":...}}`. */
enum class ErrorCode {
    E_CAPABILITY, E_ARGS, E_NOT_FOUND, E_TIMEOUT, E_CANCELLED, E_LIMIT, E_UNAVAILABLE, E_INTERNAL;

    companion object {
        /** Guest-supplied codes outside v1 collapse to E_INTERNAL rather than failing the reply. */
        fun parse(code: String?): ErrorCode = entries.firstOrNull { it.name == code } ?: E_INTERNAL
    }
}

/** Outcome of a host -> guest call (command, provider request, activation). */
sealed interface HostResult {
    data class Ok(val result: JsonElement?) : HostResult
    data class Err(val code: ErrorCode, val message: String) : HostResult
}

/**
 * Typed failure a port throws to answer the guest with `ok:false` and a specific code,
 * e.g. E_UNAVAILABLE when the environment is stopped or E_CAPABILITY on a symlink escape.
 * Any other exception from a port becomes E_INTERNAL.
 */
class HostCallException(val code: ErrorCode, message: String) : Exception(message)

/** Why a module was refused at load; the message is shown verbatim in the Extension Log. */
class ModuleRejectedException(message: String) : Exception(message)
