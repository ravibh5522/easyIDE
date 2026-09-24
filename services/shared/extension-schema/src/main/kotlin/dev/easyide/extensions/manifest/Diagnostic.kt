package dev.easyide.extensions.manifest

enum class Severity { ERROR, WARNING }

/**
 * One validation finding. [code] is a stable id (tests, CLI `--json` and docs key on it,
 * never on [message]); [pointer] is an RFC 6901 JSON Pointer into [file], or "" for the
 * file as a whole.
 */
data class Diagnostic(
    val severity: Severity,
    val code: String,
    val pointer: String,
    val file: String,
    val message: String,
) {
    override fun toString(): String = "${severity.name.first()} $code $file#$pointer: $message"

    companion object {
        fun error(code: String, pointer: String, message: String, file: String = MANIFEST_FILE) =
            Diagnostic(Severity.ERROR, code, pointer, file, message)

        fun warning(code: String, pointer: String, message: String, file: String = MANIFEST_FILE) =
            Diagnostic(Severity.WARNING, code, pointer, file, message)
    }
}

const val MANIFEST_FILE = "package.json"

/** Stable diagnostic ids. `E_` codes stop loading; `W_` codes are informational. */
object DiagnosticCode {
    // Package layout (sdk-reference "Package layout" rules)
    const val PACKAGE_IO = "E_PACKAGE_IO"
    const val PACKAGE_PATH = "E_PACKAGE_PATH"
    const val PACKAGE_SYMLINK = "E_PACKAGE_SYMLINK"
    const val PACKAGE_NESTED_ARCHIVE = "E_PACKAGE_NESTED_ARCHIVE"
    const val PACKAGE_DUPLICATE = "E_PACKAGE_DUPLICATE"
    const val PACKAGE_FILE_TOO_LARGE = "E_PACKAGE_FILE_TOO_LARGE"
    const val PACKAGE_TOO_LARGE = "E_PACKAGE_TOO_LARGE"

    // Manifest pipeline phases (extension-runtime.md sec 2.1)
    const val MANIFEST_MISSING = "E_MANIFEST_MISSING"
    const val MANIFEST_ENCODING = "E_MANIFEST_ENCODING"
    const val JSON_SYNTAX = "E_JSON_SYNTAX"
    const val NLS_MISSING = "W_NLS_MISSING"
    const val NLS_INVALID = "W_NLS_INVALID"
    const val SCHEMA = "E_SCHEMA"
    const val UNKNOWN_KEY = "W_UNKNOWN_KEY"
    const val VERSION = "E_VERSION"
    const val ENGINE_RANGE = "E_ENGINE_RANGE"
    const val ENGINE_MISMATCH = "E_ENGINE_MISMATCH"
    const val PATH_INVALID = "E_PATH_INVALID"
    const val PATH_MISSING = "E_PATH_MISSING"
    const val FILE_UNREFERENCED = "W_FILE_UNREFERENCED"
    const val COMMAND_UNRESOLVED = "E_COMMAND_UNRESOLVED"
    const val COMMAND_UNKNOWN = "W_COMMAND_UNKNOWN"
    const val ACTION_NOT_COMMAND = "E_ACTION_NOT_COMMAND"
    const val DUPLICATE_ID = "E_DUPLICATE_ID"
    const val KEY_ROW_KEY = "E_KEY_ROW_KEY"
    const val ID_PREFIX = "W_ID_PREFIX"
    const val MENU_UNKNOWN = "W_MENU_UNKNOWN"
    const val TEMPLATE = "E_TEMPLATE"
    const val ENV_NAME = "E_ENV_NAME"
    const val ONE_OF = "E_ONE_OF"
    const val REGEX = "E_REGEX"
    const val WHEN_SYNTAX = "E_WHEN_SYNTAX"
    const val WHEN_UNKNOWN_KEY = "W_WHEN_UNKNOWN_KEY"
    const val CAP_SYNTAX = "E_CAP_SYNTAX"
    const val CAP_UNDECLARED = "E_CAP_UNDECLARED"
    const val CAP_UNUSED = "W_CAP_UNUSED"
    const val SCOPE = "E_SCOPE"
    const val CONTENT = "E_CONTENT"
    const val CONTENT_IGNORED = "W_CONTENT_IGNORED"
    const val ACTIVATION_STAR = "W_ACTIVATION_STAR"
    const val ACTIVATION_EVENT = "W_ACTIVATION_EVENT"
    const val ENGINES_VSCODE = "W_ENGINES_VSCODE"
    const val WASM_TOO_LARGE = "E_WASM_TOO_LARGE"

    // Contribution registry conflicts (extension-runtime.md sec 7.1), logged not blocking
    const val COMMAND_SHADOWED = "E_COMMAND_SHADOWED"
    const val CONTRIBUTION_SHADOWED = "W_CONTRIBUTION_SHADOWED"
}

fun List<Diagnostic>.hasErrors(): Boolean = any { it.severity == Severity.ERROR }
