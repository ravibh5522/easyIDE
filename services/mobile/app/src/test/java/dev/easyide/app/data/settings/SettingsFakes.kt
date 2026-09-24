package dev.easyide.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class FakeDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: StateFlow<Preferences> = state
    var edits = 0
        private set

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        edits++
        return transform(state.value).also { state.value = it }
    }
}

/** A settings file in memory; [writes] counts successful writes. */
class FakeIo(var text: String? = null, override val path: String = "/fake/settings.json") : SettingsFileIo {
    var failWrites = false
    var writes = 0
        private set

    override suspend fun read(): FileRead = text?.let { FileRead.Text(it) } ?: FileRead.Missing

    override suspend fun write(text: String): Result<Unit> {
        if (failWrites) return Result.failure(java.io.IOException("disk full"))
        this.text = text
        writes++
        return Result.success(Unit)
    }
}

/** A layer with no storage behind it, for resolution and store tests. */
class MemoryLayer(initial: JsonObject = JsonObject(emptyMap()), override val source: String = "memory") : LayerSource {
    val state = MutableStateFlow(LayerDoc.fromJson(initial))
    override val doc = state

    override suspend fun write(edits: List<SettingEdit>): Result<Unit> {
        state.value = state.value.withEdits(edits)
        return Result.success(Unit)
    }

    override suspend fun readText(): String = JsoncEditor.render(state.value.toJson())

    override suspend fun writeText(text: String): Result<Unit> = when (val p = LayerDoc.parse(text)) {
        is ParsedLayer.Bad -> Result.failure(SettingsWriteException(p.error, "bad"))
        is ParsedLayer.Ok -> Result.success(Unit).also { state.value = p.doc }
    }
}

fun json(text: String): JsonElement = (Jsonc.parse(text) as JsoncResult.Ok).root.value

fun obj(text: String): JsonObject = json(text) as JsonObject

fun layer(id: LayerId, text: String, source: String = id.name.lowercase()) = Layer(id, LayerDoc.fromJson(obj(text)), source)

val NO_SINK = InvalidValueSink { _, _, _ -> }
