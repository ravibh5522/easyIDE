package dev.easyide.extensions

import dev.easyide.extensions.manifest.SemVer

/**
 * Limits no settings layer may relax (sdk-reference "Fixed limits and protected keys").
 * User-tunable limits are settings keys instead; see settings/ExtensionSettings.kt.
 * SAFE_MODE_AFTER_CONSECUTIVE_CRASHES comes from arch.md sec 5.5; the rest are starting
 * values from extension-runtime.md sec 10, to be profiled.
 */
object ExtensionPolicy {
    const val SAFE_MODE_AFTER_CONSECUTIVE_CRASHES = 2
    const val STARTUP_IDLE_DELAY_MS = 1_000L
    const val WORKSPACE_SCAN_MAX_FILES = 20_000

    /** Bounds regex backtracking for schema `pattern`, when-clause `=~` and input `validate`. */
    const val MAX_PATTERN_LENGTH = 512
    const val MAX_COMMAND_VARIABLE_DEPTH = 4
    const val FIRST_LINE_MAX_CHARS = 256
    const val GRAMMAR_LINE_TIME_LIMIT_MS = 50L

    /** When-clause text longer than this is refused before lexing (bounds parser work). */
    const val MAX_WHEN_LENGTH = 4_096

    /** Stderr kept on a failed step for the Extension Log (the tail is what explains a failure). */
    const val STDERR_TAIL_CHARS = 2_000

    /**
     * Keys `setConfig`/`toggleConfig` never write, even with `ui.settings` (sdk-reference
     * "Always on, no key"; threat-model M-11). A trailing `.*` covers the whole subtree.
     * Keybinding files are not settings keys, so no action can reach them at all.
     */
    val EXTENSION_UNWRITABLE: List<String> = listOf("extensions.*", "lsp.servers", "lsp.servers.*", "profiles.active")

    fun isUnwritable(key: String): Boolean = EXTENSION_UNWRITABLE.any { rule ->
        if (rule.endsWith(".*")) key.startsWith(rule.dropLast(1)) || key == rule.dropLast(2) else key == rule
    }
}

/**
 * The extension API version this build implements (R-API-01). `engines.easyide` must
 * contain it. Every sdk-reference example targets `^0.3.0`, so API v0 starts at 0.3.0.
 */
object AppApi {
    val VERSION: SemVer = SemVer(0, 3, 0, emptyList())
}
