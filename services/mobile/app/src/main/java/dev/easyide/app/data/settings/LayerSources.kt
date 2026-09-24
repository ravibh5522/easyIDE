package dev.easyide.app.data.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A write that could not happen; [diagnostic] says why when the cause is the content. */
class SettingsWriteException(val diagnostic: SettingsDiagnostic?, message: String) : Exception(message)

/**
 * One writable settings layer (LLD 4.2). [doc] emits after the first read and
 * on every change; [write] applies property edits; [readText]/[writeText] back
 * the "Edit as JSON" screen.
 */
interface LayerSource {
    /** Provenance label: a file path or "profile <name>". */
    val source: String
    val doc: Flow<LayerDoc>
    suspend fun write(edits: List<SettingEdit>): Result<Unit>
    suspend fun readText(): String

    /** Refuses a text that does not parse (the buffer stays with the user); anything else is saved as written. */
    suspend fun writeText(text: String): Result<Unit>
}

/** Outcome of reading a settings file. */
sealed interface FileRead {
    data object Missing : FileRead
    data class Text(val text: String) : FileRead
    /** Over [SettingsPolicy.MAX_FILE_BYTES]: not read at all (ST-27). */
    data class TooLarge(val bytes: Long) : FileRead
    data class Failed(val reason: String) : FileRead
}

/** The I/O boundary under a file-backed layer, so the layer logic is testable without a filesystem. */
interface SettingsFileIo {
    val path: String
    suspend fun read(): FileRead
    suspend fun write(text: String): Result<Unit>
}

/**
 * A settings file as a layer. A file that fails to parse keeps its last good
 * parse (empty at cold start) with the error attached, so a half-typed edit
 * or a bad `git pull` degrades to "previous settings plus a banner" instead of
 * every value snapping back to defaults. Watching starts with the first
 * collector and stops with the last, so closed projects cost nothing.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class FileLayerSource(
    private val io: SettingsFileIo,
    scope: CoroutineScope,
    /** Starts watching; the callback fires on any change; closing stops. Null for files only the app writes. */
    private val watch: ((onChange: () -> Unit) -> AutoCloseable)? = null,
) : LayerSource {

    override val source: String get() = io.path

    private val state = MutableStateFlow<LayerDoc?>(null)
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val lock = Mutex()
    private var lastGood: LayerDoc = LayerDoc.EMPTY
    private var lastTextHash: Int? = null

    override val doc: Flow<LayerDoc> = state.filterNotNull()

    init {
        scope.launch {
            state.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { active ->
                if (!active) return@collectLatest
                reload()
                val handle = watch?.invoke { changes.tryEmit(Unit) } ?: return@collectLatest
                try {
                    changes.debounce(SettingsPolicy.FILE_RELOAD_DEBOUNCE_MS).collect { reload() }
                } finally {
                    handle.close()
                }
            }
        }
    }

    /** Re-reads the file; a text identical to the last one seen (our own write) is skipped. */
    suspend fun reload() {
        lock.withLock { load() }
    }

    override suspend fun write(edits: List<SettingEdit>): Result<Unit> = lock.withLock {
        val current = when (val r = io.read()) {
            FileRead.Missing -> ""
            is FileRead.Text -> r.text
            is FileRead.TooLarge -> return@withLock refuse(SettingsDiagnostic(DiagnosticCode.PARSE_ERROR, parseError = JsoncError.TOO_LARGE))
            is FileRead.Failed -> return@withLock Result.failure(SettingsWriteException(null, r.reason))
        }
        // Never rewrite a broken file: the user's half-finished edit would be lost.
        val next = JsoncEditor.apply(current, edits)
            ?: return@withLock refuse((LayerDoc.parse(current) as? ParsedLayer.Bad)?.error)
        store(next)
    }

    override suspend fun readText(): String = when (val r = io.read()) {
        is FileRead.Text -> r.text
        else -> ""
    }

    override suspend fun writeText(text: String): Result<Unit> = lock.withLock {
        val bad = LayerDoc.parse(text) as? ParsedLayer.Bad
        if (bad != null) refuse(bad.error) else store(text)
    }

    private suspend fun store(text: String): Result<Unit> = io.write(text).onSuccess { accept(text) }

    private suspend fun load() {
        when (val r = io.read()) {
            FileRead.Missing -> { lastTextHash = null; lastGood = LayerDoc.EMPTY.withErrors(emptyList()); state.value = lastGood }
            is FileRead.Text -> if (r.text.hashCode() != lastTextHash || state.value == null) accept(r.text)
            is FileRead.TooLarge -> keepLastGood(SettingsDiagnostic(DiagnosticCode.PARSE_ERROR, parseError = JsoncError.TOO_LARGE))
            is FileRead.Failed -> keepLastGood(SettingsDiagnostic(DiagnosticCode.FILE_UNREADABLE, detail = r.reason))
        }
    }

    private fun accept(text: String) {
        lastTextHash = text.hashCode()
        when (val parsed = LayerDoc.parse(text)) {
            is ParsedLayer.Ok -> { lastGood = parsed.doc; state.value = parsed.doc }
            is ParsedLayer.Bad -> keepLastGood(parsed.error)
        }
    }

    private fun keepLastGood(error: SettingsDiagnostic) {
        state.value = lastGood.withErrors(listOf(error))
    }

    private fun refuse(error: SettingsDiagnostic?): Result<Unit> =
        Result.failure(SettingsWriteException(error, "settings file ${io.path} is not valid JSON"))

    /** For callers that need the current value once (export, profile switch validation). */
    suspend fun current(): LayerDoc = doc.first()
}
