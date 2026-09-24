package dev.easyide.app.data.settings

/**
 * Fixed limits and protected keys for the settings system
 * (docs/extension-sdk/lld/customization.md sec 18, sdk-reference "Fixed limits
 * and protected keys"). Deliberately not settings: no layer - a cloned project
 * file included - may relax them.
 */
object SettingsPolicy {

    /** Settings files larger than this are treated as a parse error without being read (threat-model ST-27). */
    const val MAX_FILE_BYTES = 1L * 1024 * 1024

    /** Deeper nesting is a parse error: a crafted file must not exhaust the parser's stack. */
    const val MAX_JSON_DEPTH = 64

    /** Bursts of writes (git checkout, an editor's temp-and-rename) collapse into one reload. */
    const val FILE_RELOAD_DEBOUNCE_MS = 200L

    /** The JSON editor re-validates this long after the last keystroke. */
    const val JSON_VALIDATE_DEBOUNCE_MS = 300L

    /** Upper bound on the uncompressed size of an imported settings bundle (zip-bomb guard). */
    const val IMPORT_MAX_BYTES = 10L * 1024 * 1024

    /**
     * Keys no extension may write, even holding `ui.settings` (threat-model M-11):
     * they decide which code runs. A trailing `*` matches any key with that prefix.
     * Keybinding files are protected the same way: no write path from extensions exists.
     */
    val EXTENSION_UNWRITABLE: List<String> = listOf("extensions.*", "lsp.servers", "profiles.active")

    /**
     * Protected keys a project file may still hold, because sdk-reference gives them
     * P scope: a project re-enabling/disabling extensions (CUS-08) and defining
     * language servers (CUS-10, exec-bearing fields gated by [ProjectTrust]). Every
     * other protected key is ignored in the project layer.
     */
    val PROJECT_READABLE_PROTECTED: Set<String> = setOf("extensions.disabled", LSP_SERVERS_KEY)

    /**
     * Keys that belong to the device, not to a profile (LLD sec 14): they always live
     * in the default store and are never exported. A trailing `*` matches a prefix.
     */
    val APP_LEVEL: List<String> = listOf(
        "profiles.active",
        "extensions.registries",
        "extensions.safeMode",
        "extensions.developerMode",
        "extensions.limits.*",
        "extensions.wasm.*",
        "lsp.globalMemoryBudgetMb",
    )

    /** Owned by the LSP client (lld/lsp-client.md); named here because trust inspects its entries. */
    const val LSP_SERVERS_KEY = "lsp.servers"

    /** Fields of an `lsp.servers` entry that decide what gets spawned (LLD sec 12). */
    val LSP_EXEC_FIELDS: Set<String> = setOf("command", "env", "initializationOptions")

    /** Profile names: also file names, so no separators or dots. */
    val PROFILE_NAME = Regex("^[A-Za-z0-9 _-]{1,40}$")

    const val DEFAULT_PROFILE = "default"

    fun isExtensionUnwritable(key: String): Boolean = EXTENSION_UNWRITABLE.any { matches(it, key) }

    fun isAppLevel(key: String): Boolean = APP_LEVEL.any { matches(it, key) }

    /**
     * Whether [layer] may hold a value for [key] at all, independent of its scope.
     * Extension defaults can never touch protected keys; the project layer only the
     * two sdk-reference makes project-scoped.
     */
    fun layerMayHold(layer: LayerId, key: String): Boolean = when (layer) {
        LayerId.EXTENSION -> !isExtensionUnwritable(key)
        LayerId.PROJECT -> !isExtensionUnwritable(key) || key in PROJECT_READABLE_PROTECTED
        else -> true
    }

    private fun matches(pattern: String, key: String): Boolean =
        if (pattern.endsWith("*")) key.startsWith(pattern.dropLast(1)) else key == pattern
}
