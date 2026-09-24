package dev.easyide.app.ui.screens.settings

import dev.easyide.app.data.settings.ConfigTarget
import dev.easyide.app.data.settings.JsonSuggestion
import dev.easyide.app.data.settings.ProfileManager
import dev.easyide.app.data.settings.SchemaState
import dev.easyide.app.data.settings.SettingsJsonCompletion
import dev.easyide.app.data.settings.SettingsDiagnostic
import dev.easyide.app.data.settings.SettingsJsonDiagnostics
import dev.easyide.app.data.settings.SettingsPolicy
import dev.easyide.app.data.settings.SettingsStore
import dev.easyide.app.data.settings.SettingsWriteException
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.app.ui.commands.KeybindingsFile
import dev.easyide.app.ui.commands.Keymap
import dev.easyide.app.ui.commands.KeymapResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What an "Edit as JSON" buffer edits. */
sealed interface JsonDocument {
    data class SettingsLayer(val target: ConfigTarget) : JsonDocument
    data object Keybindings : JsonDocument
}

data class JsonEditorState(
    val document: JsonDocument,
    val text: String,
    val diagnostics: List<SettingsDiagnostic> = emptyList(),
    val saving: Boolean = false,
    /** Set when the last save attempt failed; the buffer is kept either way. */
    val saveFailed: Boolean = false,
) {
    /** A buffer that would not parse back is never saved (LLD 16). */
    val canSave: Boolean get() = !saving && !SettingsJsonDiagnostics.blocksSave(diagnostics)
}

/**
 * The settings / keybindings JSON editor's state (LLD sec 16): loads the layer's
 * text (a virtual document for the user layer, the real file otherwise),
 * re-validates [SettingsPolicy.JSON_VALIDATE_DEBOUNCE_MS] after the last
 * keystroke, and saves only a buffer that parses.
 */
class SettingsJsonEditorController(
    private val scope: CoroutineScope,
    private val store: SettingsStore,
    private val profiles: ProfileManager,
) {
    private val _state = MutableStateFlow<JsonEditorState?>(null)
    val state: StateFlow<JsonEditorState?> = _state.asStateFlow()

    private var validation: Job? = null

    /** The schema as of opening the buffer, for completion (it changes only when packs do). */
    @Volatile private var schema: SchemaState? = null

    /**
     * Key and enum-value suggestions at [cursor] (LLD 16); keybindings.json gets command ids.
     * Cheap and synchronous, so the editor calls it on every caret move.
     */
    fun suggest(text: String, cursor: Int): List<JsonSuggestion> {
        val doc = _state.value?.document ?: return emptyList()
        val s = schema ?: return emptyList()
        return when (doc) {
            is JsonDocument.SettingsLayer -> SettingsJsonCompletion.suggest(text, cursor, SettingsJsonCompletion.Kind.SETTINGS, s)
            JsonDocument.Keybindings -> SettingsJsonCompletion.suggest(text, cursor, SettingsJsonCompletion.Kind.KEYBINDINGS, s, CommandIds.ALL)
        }
    }

    fun openLayer(target: ConfigTarget) = open(JsonDocument.SettingsLayer(target))

    fun openKeybindings() = open(JsonDocument.Keybindings)

    fun onTextChanged(text: String) {
        _state.update { it?.copy(text = text, saveFailed = false) }
        validation?.cancel()
        validation = scope.launch {
            delay(SettingsPolicy.JSON_VALIDATE_DEBOUNCE_MS)
            validate(text)
        }
    }

    fun save() {
        val current = _state.value ?: return
        scope.launch {
            validation?.cancel()
            validate(current.text)
            if (_state.value?.canSave != true) return@launch
            _state.update { it?.copy(saving = true) }
            val result = when (val doc = current.document) {
                is JsonDocument.SettingsLayer -> store.sourceFor(doc.target)?.writeText(current.text)
                    ?: Result.failure(SettingsWriteException(null, "no such layer"))
                JsonDocument.Keybindings -> profiles.keybindings.writeText(current.text)
            }
            if (result.isSuccess) _state.value = null else _state.update { it?.copy(saving = false, saveFailed = true) }
        }
    }

    fun close() {
        validation?.cancel()
        _state.value = null
    }

    /**
     * The Keyboard Shortcuts screen's edits: [transform] maps the active profile's
     * keybindings.json text to the new text (null = the file cannot be edited that way).
     */
    fun editKeybindings(transform: (String) -> String?, onResult: (Boolean) -> Unit) {
        scope.launch {
            val next = transform(profiles.keybindings.readText())
            onResult(next != null && profiles.keybindings.writeText(next).isSuccess)
        }
    }

    private fun open(document: JsonDocument) {
        scope.launch {
            schema = store.schema.first()
            val text = when (document) {
                is JsonDocument.SettingsLayer -> store.sourceFor(document.target)?.readText().orEmpty()
                JsonDocument.Keybindings -> profiles.keybindings.readText()
            }
            _state.value = JsonEditorState(document, text)
            validate(text)
        }
    }

    private suspend fun validate(text: String) {
        val document = _state.value?.document ?: return
        val diagnostics = withContext(Dispatchers.Default) {
            when (document) {
                is JsonDocument.SettingsLayer -> SettingsJsonDiagnostics.check(text, document.target.layer, store.schema.first())
                JsonDocument.Keybindings -> KeymapResolver.resolve(Keymap.DEFAULT, KeybindingsFile.parse(text), CommandIds.KNOWN).diagnostics
            }
        }
        _state.update { s -> s?.takeIf { it.text == text }?.copy(diagnostics = diagnostics) ?: s }
    }
}
