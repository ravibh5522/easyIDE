package dev.easyide.lsp

/**
 * The one declarative table of internal LSP limits (docs/extension-sdk/lld/lsp-client.md sec 9,
 * sdk-reference "Fixed limits"). These are deliberately not settings: no settings layer - a
 * cloned project file included - may relax a protocol-sanity or memory limit. Values are
 * starting points to be profiled on the reference device.
 *
 * Everything user-tunable lives in [LspSettings] instead, under the sdk-reference key names.
 */
object LspPolicy {

    /** Upper bound of one frame's whole header section, in bytes. */
    const val MAX_HEADER_BYTES: Int = 8 * 1024

    /** Upper bound of one frame's body (`Content-Length`), in bytes. */
    const val MAX_MESSAGE_BYTES: Int = 64 * 1024 * 1024

    /**
     * Deepest JSON nesting accepted from a server. Checked on the raw bytes before parsing,
     * because a recursive-descent parser would overflow the stack on hostile input first
     * (threat-model M-17).
     */
    const val MAX_JSON_DEPTH: Int = 256

    /**
     * Largest document sent to a Full-sync server, compared against
     * `text.length * UTF8_BYTES_PER_UTF16_UNIT` so no encode is needed to decide.
     */
    const val MAX_FULL_SYNC_BYTES: Int = 2 * 1024 * 1024

    /** UTF-8 upper bound per UTF-16 code unit (a surrogate pair is 2 units, 4 bytes). */
    const val UTF8_BYTES_PER_UTF16_UNIT: Int = 3

    /** Multiplier for methods not listed in [METHOD_TIMEOUT_MULTIPLIER]. */
    const val DEFAULT_TIMEOUT_MULTIPLIER: Int = 1

    /**
     * Per-method multiplier over `lsp.requestTimeoutMs`. Project-wide operations get longer
     * because they walk every file, not because they are more important.
     */
    val METHOD_TIMEOUT_MULTIPLIER: Map<String, Int> = mapOf(
        "textDocument/references" to 3,
        "textDocument/rename" to 3,
        "textDocument/formatting" to 3,
        "textDocument/rangeFormatting" to 3,
        "workspace/symbol" to 2,
    )

    /** Grace for `shutdown`, then for the process to exit, then between SIGTERM and SIGKILL. */
    const val SHUTDOWN_GRACE_MS: Long = 2000

    /** Crashes older than this are forgotten; a full window of RUNNING clears history. */
    const val CRASH_WINDOW_MS: Long = 300_000

    /**
     * Cap on the backoff exponent: `lsp.restart.backoffMs * 2^(n-1)` stops doubling after this
     * many crashes, so a large `lsp.restart.maxRetries` cannot overflow or wait for hours.
     */
    const val MAX_BACKOFF_DOUBLINGS: Int = 10

    /** RSS sampler period. */
    const val MEMORY_SAMPLE_MS: Long = 5000

    /** Consecutive over-budget samples before a server counts as over its own budget. */
    const val OVER_BUDGET_SAMPLES: Int = 2

    /** Global eviction stops once the RSS sum is at or below budget times this (hysteresis). */
    const val EVICT_TARGET_RATIO: Double = 0.85

    /** Per-session log ring (stderr, logMessage, transitions, timeouts, trace). */
    const val LOG_RING_LINES: Int = 2000

    /** stderr lines carried by a Failed state for the UI. */
    const val STDERR_TAIL_LINES: Int = 40

    /** How long a crash waits for the rest of stderr (the traceback) before it is reported. */
    const val STDERR_DRAIN_GRACE_MS: Long = 250

    /** Longer log lines are truncated; stderr is never parsed, only shown. */
    const val MAX_LOG_LINE_CHARS: Int = 2000

    /** Delay after a flush before pulling diagnostics (lsp-features.md 3.1 `debounce(PULL_DIAGNOSTICS)`). */
    const val PULL_DIAGNOSTICS_DEBOUNCE_MS: Long = 200

    /** `textDocument.foldingRange.rangeLimit` advertised to servers. */
    const val FOLDING_RANGE_LIMIT: Int = 5000

    const val KB_PER_MB: Long = 1024

    /** Timeout for [method] given the `lsp.requestTimeoutMs` setting. */
    fun timeoutFor(method: String, requestTimeoutMs: Long): Long =
        requestTimeoutMs * (METHOD_TIMEOUT_MULTIPLIER[method] ?: DEFAULT_TIMEOUT_MULTIPLIER)
}
