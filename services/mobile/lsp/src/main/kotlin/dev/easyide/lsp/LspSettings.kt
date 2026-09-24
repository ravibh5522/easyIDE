package dev.easyide.lsp

/**
 * Resolved global LSP settings (sdk-reference "Settings keys", G scope). The app builds this
 * from its settings resolution and republishes it on change; `:lsp` holds no defaults of its
 * own so the schema stays the single source of truth for them.
 *
 * Per-server values that have a same-named `lsp.*` default (`idleShutdownSec`,
 * `startupTimeoutSec`, `memoryBudgetMb`) are already resolved into [dev.easyide.lsp.session.ServerConfig].
 *
 * @property requestTimeoutMs `lsp.requestTimeoutMs`
 * @property didChangeDebounceMs `lsp.didChangeDebounceMs`
 * @property restartMaxRetries `lsp.restart.maxRetries`
 * @property restartBackoffMs `lsp.restart.backoffMs` (doubled per crash in the window)
 * @property globalMemoryBudgetMb `lsp.globalMemoryBudgetMb`
 * @property maxServers `lsp.maxServers`
 * @property trace `lsp.trace`
 */
data class LspSettings(
    val requestTimeoutMs: Long,
    val didChangeDebounceMs: Long,
    val restartMaxRetries: Int,
    val restartBackoffMs: Long,
    val globalMemoryBudgetMb: Int,
    val maxServers: Int,
    val trace: TraceLevel,
)

/** `lsp.trace` values, sent verbatim as `trace` in `initialize` and `$/setTrace`. */
enum class TraceLevel(val wire: String) {
    OFF("off"),
    MESSAGES("messages"),
    VERBOSE("verbose"),
}
