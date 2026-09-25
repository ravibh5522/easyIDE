package dev.easyide.app.ui.screens.diagnostics

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.easyide.app.diagnostics.AppLog
import dev.easyide.app.diagnostics.Cleanup
import dev.easyide.app.diagnostics.CrashReports
import dev.easyide.app.diagnostics.DiagnosticsCollector
import dev.easyide.app.diagnostics.DiagnosticsReportText
import dev.easyide.app.diagnostics.DiagnosticsSharing
import dev.easyide.app.diagnostics.LogLevel
import dev.easyide.app.diagnostics.LogSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * State and actions of the Diagnostics screen. Collection, hashing, cleanup and export all
 * touch the disk, so each runs on [io]; a failure of any of them is an I/O boundary and
 * becomes a [DiagnosticsMessage] rather than a crash. Actions that change the disk are logged
 * to [appLog], so a later bug report shows what the user cleared.
 */
class DiagnosticsViewModel(
    private val collector: DiagnosticsCollector,
    private val appLog: AppLog,
    crashReports: CrashReports,
    private val cleanup: Cleanup,
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val sharing = DiagnosticsSharing(appLog, crashReports, io)
    private val mutableState = MutableStateFlow(DiagnosticsUiState())
    private val mutableMessage = MutableStateFlow<DiagnosticsMessage?>(null)
    private var refreshJob: Job? = null

    val uiState: StateFlow<DiagnosticsUiState> = mutableState.asStateFlow()
    val message: StateFlow<DiagnosticsMessage?> = mutableMessage.asStateFlow()

    fun onMessageShown() {
        mutableMessage.value = null
    }

    /** Collects a fresh report. Verified-checksum results are dropped: the disk may have changed under them. */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true) }
            val report = collector.collect()
            mutableState.update { it.copy(loading = false, report = report, checksums = emptyMap()) }
        }
    }

    /** Hashes the environment's cached image archive; only when the user asks, because it reads ~30 MB. */
    fun verifyChecksum(environmentId: String) {
        val env = mutableState.value.report?.environments?.firstOrNull { it.id == environmentId } ?: return
        if (mutableState.value.checksums[environmentId] == ChecksumProgress.Running) return
        mutableState.update { it.copy(checksums = it.checksums + (environmentId to ChecksumProgress.Running)) }
        viewModelScope.launch {
            try {
                val status = collector.verifyChecksum(env)
                mutableState.update { it.copy(checksums = it.checksums + (environmentId to ChecksumProgress.Done(status))) }
            } catch (unreadable: IOException) {
                mutableState.update { it.copy(checksums = it.checksums - environmentId) }
                mutableMessage.value = DiagnosticsMessage.VerifyFailed
            }
        }
    }

    /** Measures what [target] would free and asks the screen to confirm it. */
    fun requestCleanup(target: Cleanup.Target) {
        viewModelScope.launch {
            try {
                val bytes = withContext(io) { cleanup.estimate(target) }
                mutableState.update { it.copy(pendingCleanup = PendingCleanup(target, bytes)) }
            } catch (failure: IOException) {
                logFailure("Cleanup estimate for $target failed", failure)
                mutableMessage.value = DiagnosticsMessage.CleanupFailed
            }
        }
    }

    fun cancelCleanup() {
        mutableState.update { it.copy(pendingCleanup = null) }
    }

    fun confirmCleanup() {
        val pending = mutableState.value.pendingCleanup ?: return
        mutableState.update { it.copy(pendingCleanup = null, busy = true) }
        viewModelScope.launch {
            try {
                val result = withContext(io) { cleanup.run(pending.target) }
                appLog.log(LogLevel.INFO, LogSource.APP, "Cleanup ${pending.target}: freed ${result.freedBytes} bytes, ${result.failedEntries} failed")
                mutableMessage.value = DiagnosticsMessage.CleanupDone(result.freedBytes, result.failedEntries)
            } catch (failure: IOException) {
                logFailure("Cleanup ${pending.target} failed", failure)
                mutableMessage.value = DiagnosticsMessage.CleanupFailed
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
            refresh()
        }
    }

    /** Writes the summary, crash reports and full log to [uri] (a document the user picked). */
    fun exportLogs(uri: Uri) {
        val report = mutableState.value.report ?: return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(io) {
                    val out = resolver.openOutputStream(uri, WRITE_TRUNCATE) ?: throw IOException("No output stream for $uri")
                    out.use { sharing.export(it, DiagnosticsReportText.render(report)) }
                }
                mutableMessage.value = DiagnosticsMessage.ExportDone
            } catch (failure: IOException) {
                logFailure("Log export failed", failure)
                mutableMessage.value = DiagnosticsMessage.ExportFailed
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /** The text for a share sheet: summary, the newest crash report and the log tail, bounded. */
    suspend fun shareText(): String =
        sharing.text(mutableState.value.report?.let(DiagnosticsReportText::render).orEmpty())

    private fun logFailure(what: String, failure: IOException) {
        appLog.log(LogLevel.WARN, LogSource.APP, "$what: $failure")
    }

    private companion object {
        /** `w` alone may not truncate an existing document on every provider; `wt` does. */
        const val WRITE_TRUNCATE = "wt"
    }
}
