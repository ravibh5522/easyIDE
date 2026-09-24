package dev.easyide.extwasm

/**
 * Internal constants of the WASM host that are deliberately not user settings
 * (lld/wasm-host.md sec 7, "declared once in WasmPolicy"). One declarative table, no
 * literals at call sites.
 */
object WasmPolicy {
    /** ABI version this host implements (`ext_abi_version` and the `v` field). */
    const val ABI_VERSION = 1

    /** Parsed metered modules kept in memory, keyed by metered sha256. */
    const val MODULE_CACHE_ENTRIES = 8

    /** Pending events per instance before the oldest non-coalescible one is dropped. */
    const val EVENT_QUEUE_CAPACITY = 256

    /** Watchdog period; also bounds how late a timeout is noticed. */
    const val WATCHDOG_TICK_MS = 10L

    /** Outstanding async handles (`sandbox.exec`, `net.fetch`) per instance. */
    const val MAX_PENDING_HANDLES = 16

    /**
     * JVM stack of each worker thread. Chicory spends several JVM frames per wasm call, so this
     * sets guest recursion depth: about 10000 frames on the 2026-09-24 ART spike device.
     */
    const val WORKER_STACK_BYTES = 8L shl 20

    /**
     * Environment keys `sandbox.exec` never passes through, whatever the guest asks for:
     * `EASYIDE_GIT_TOKEN` is the variable GitRemote uses (decision 0012), the others are the
     * Claude Code credentials. No capability grants either (sdk-reference Capabilities).
     */
    val RESERVED_ENV_KEYS: Set<String> = setOf(
        "EASYIDE_GIT_TOKEN", "ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN",
    )

    /** Settings keys `config.set` never writes, even with `ui.settings` (sdk-reference). */
    val PROTECTED_KEY_PREFIXES: List<String> = listOf("extensions.", "keybindings")
    val PROTECTED_KEYS: Set<String> = setOf("lsp.servers", "profiles.active")

    /** Guest path of the project root; anything else needs `fs.outsideProject`. */
    const val PROJECT_ROOT = "/workspace"

    /** Provider kinds v1 accepts in `providers.register` (lld/wasm-host.md sec 11.3). */
    val PROVIDER_KINDS: Set<String> = setOf("completion", "hover")

    /** Prefix of export and global names the metering pass reserves. */
    const val RESERVED_NAME_PREFIX = "__easyide_"
}
