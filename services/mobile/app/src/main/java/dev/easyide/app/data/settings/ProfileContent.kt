package dev.easyide.app.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * `profiles/<name>.json` = `{settings, keybindings, enabledExtensions, keyRows}`
 * (sdk-reference). [enabledExtensions] null means "no allowlist"; [keyRows] is
 * carried for the key-row feature and round-trips untouched until then.
 */
data class ProfileContent(
    val settings: JsonObject,
    val keybindings: JsonArray,
    val enabledExtensions: JsonArray?,
    val keyRows: JsonArray?,
) {
    fun toJson(): JsonObject = JsonObject(
        buildMap {
            put(SETTINGS, settings)
            put(KEYBINDINGS, keybindings)
            put(ENABLED_EXTENSIONS, enabledExtensions ?: JsonNull)
            put(KEY_ROWS, keyRows ?: JsonNull)
        },
    )

    companion object {
        private const val SETTINGS = "settings"
        private const val KEYBINDINGS = "keybindings"
        private const val ENABLED_EXTENSIONS = "enabledExtensions"
        private const val KEY_ROWS = "keyRows"

        val EMPTY = ProfileContent(JsonObject(emptyMap()), JsonArray(emptyList()), null, null)

        /** Validates the file's shape; a profile that fails here is never switched to (LLD 14). */
        fun parse(text: String): Result<ProfileContent> {
            val root = when (val r = Jsonc.parse(text)) {
                is JsoncResult.Failure -> return failure(SettingsDiagnostic(DiagnosticCode.PARSE_ERROR, offset = r.offset, parseError = r.error))
                is JsoncResult.Ok -> r.root.value as? JsonObject ?: return failure(SettingsDiagnostic(DiagnosticCode.NOT_AN_OBJECT))
            }
            val settings = root[SETTINGS].orNullIfJsonNull() ?: JsonObject(emptyMap())
            val keybindings = root[KEYBINDINGS].orNullIfJsonNull() ?: JsonArray(emptyList())
            val enabled = root[ENABLED_EXTENSIONS].orNullIfJsonNull()
            val keyRows = root[KEY_ROWS].orNullIfJsonNull()
            if (settings !is JsonObject) return failure(SettingsDiagnostic(DiagnosticCode.NOT_AN_OBJECT, key = SETTINGS))
            if (keybindings !is JsonArray) return failure(SettingsDiagnostic(DiagnosticCode.BAD_ENTRY, key = KEYBINDINGS))
            if (enabled != null && enabled !is JsonArray) return failure(SettingsDiagnostic(DiagnosticCode.BAD_ENTRY, key = ENABLED_EXTENSIONS))
            if (keyRows != null && keyRows !is JsonArray) return failure(SettingsDiagnostic(DiagnosticCode.BAD_ENTRY, key = KEY_ROWS))
            return Result.success(ProfileContent(settings, keybindings, enabled as JsonArray?, keyRows as JsonArray?))
        }

        private fun JsonElement?.orNullIfJsonNull(): JsonElement? = this?.takeIf { it != JsonNull }

        private fun failure(d: SettingsDiagnostic): Result<ProfileContent> =
            Result.failure(SettingsWriteException(d, "invalid profile file"))
    }
}

/** A keybindings.json text, wherever it is stored (its own file, or inside a profile). */
interface KeybindingsSource {
    val text: Flow<String>
    suspend fun readText(): String
    suspend fun writeText(text: String): Result<Unit>
}

/** `<files>/user/keybindings.json` of the default profile; the text is kept as written, comments included. */
class FileKeybindings(private val io: SettingsFileIo) : KeybindingsSource {
    private val state = MutableStateFlow<String?>(null)
    private val lock = Mutex()

    override val text: Flow<String> = state.filterNotNull()

    suspend fun load() = lock.withLock {
        state.value = when (val r = io.read()) {
            is FileRead.Text -> r.text
            else -> ""
        }
    }

    override suspend fun readText(): String {
        if (state.value == null) load()
        return state.value.orEmpty()
    }

    override suspend fun writeText(text: String): Result<Unit> = lock.withLock {
        io.write(text).onSuccess { state.value = text }
    }
}

/**
 * One named profile's file. Loaded once and then owned by the app (profiles
 * live in app-private storage); writes rewrite the whole file, which is safe
 * because nothing else edits it.
 */
class NamedProfile(val name: String, private val io: SettingsFileIo) {

    private val state = MutableStateFlow<ProfileContent?>(null)
    private val lock = Mutex()

    suspend fun load(): Result<ProfileContent> = lock.withLock {
        val text = when (val r = io.read()) {
            FileRead.Missing -> return@withLock Result.success(ProfileContent.EMPTY).onSuccess { state.value = it }
            is FileRead.Text -> r.text
            is FileRead.TooLarge -> return@withLock Result.failure(
                SettingsWriteException(SettingsDiagnostic(DiagnosticCode.PARSE_ERROR, parseError = JsoncError.TOO_LARGE), io.path),
            )
            is FileRead.Failed -> return@withLock Result.failure(SettingsWriteException(null, r.reason))
        }
        ProfileContent.parse(text).onSuccess { state.value = it }
    }

    suspend fun content(): ProfileContent = state.value ?: load().getOrThrow()

    suspend fun save(content: ProfileContent): Result<Unit> = lock.withLock {
        io.write(JsoncEditor.render(content.toJson())).onSuccess { state.value = content }
    }

    private suspend fun update(transform: (ProfileContent) -> ProfileContent): Result<Unit> =
        runCatching { content() }.fold({ save(transform(it)) }, { Result.failure(it) })

    val settings: LayerSource = object : LayerSource {
        override val source: String = "profile $name"
        override val doc: Flow<LayerDoc> = state.filterNotNull().map { LayerDoc.fromJson(it.settings) }
        override suspend fun write(edits: List<SettingEdit>): Result<Unit> =
            update { it.copy(settings = LayerDoc.fromJson(it.settings).withEdits(edits).toJson()) }
        override suspend fun readText(): String = JsoncEditor.render(doc.first().toJson())
        override suspend fun writeText(text: String): Result<Unit> = when (val p = LayerDoc.parse(text)) {
            is ParsedLayer.Bad -> Result.failure(SettingsWriteException(p.error, "settings JSON does not parse"))
            is ParsedLayer.Ok -> update { it.copy(settings = p.doc.toJson()) }
        }
    }

    val keybindings: KeybindingsSource = object : KeybindingsSource {
        override val text: Flow<String> = state.filterNotNull().map { JsoncEditor.render(it.keybindings) }
        override suspend fun readText(): String = JsoncEditor.render(content().keybindings)
        override suspend fun writeText(text: String): Result<Unit> {
            val parsed = Jsonc.parse(text)
            val array = (parsed as? JsoncResult.Ok)?.root?.value as? JsonArray
                ?: return Result.failure(SettingsWriteException(SettingsDiagnostic(DiagnosticCode.BAD_ENTRY), "keybindings must be an array"))
            return update { it.copy(keybindings = array) }
        }
    }
}
