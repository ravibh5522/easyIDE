package dev.easyide.app.ui.screens.diagnostics

import dev.easyide.app.diagnostics.ChecksumStatus
import dev.easyide.app.diagnostics.Cleanup
import dev.easyide.app.diagnostics.DiagnosticsReport

/** A cleanup waiting for the user's confirmation, with the bytes it will free. */
data class PendingCleanup(val target: Cleanup.Target, val bytes: Long)

/** The state of one environment's on-demand checksum verification. */
sealed interface ChecksumProgress {
    data object Running : ChecksumProgress
    data class Done(val status: ChecksumStatus) : ChecksumProgress
}

/**
 * @property report null until the first collection finishes.
 * @property loading a collection is running; the previous [report] stays visible meanwhile.
 * @property checksums per environment id; absent means "as collected".
 * @property busy a cleanup or export is running, so the actions that could race it are disabled.
 */
data class DiagnosticsUiState(
    val loading: Boolean = true,
    val report: DiagnosticsReport? = null,
    val checksums: Map<String, ChecksumProgress> = emptyMap(),
    val pendingCleanup: PendingCleanup? = null,
    val busy: Boolean = false,
)

/** One-shot outcomes shown as a snackbar. The screen maps each to a string resource. */
sealed interface DiagnosticsMessage {
    data class CleanupDone(val freedBytes: Long, val failedEntries: Int) : DiagnosticsMessage
    data object CleanupFailed : DiagnosticsMessage
    data object VerifyFailed : DiagnosticsMessage
    data object ExportDone : DiagnosticsMessage
    data object ExportFailed : DiagnosticsMessage
}
