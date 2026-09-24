package dev.easyide.app.data.settings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Which environment and project a resolution is for. The language is chosen
 * per lookup ([SettingsSnapshot.get]) because one editor shows files of many
 * languages against the same layer stack.
 */
data class SettingsQuery(val envId: String? = null, val projectId: String? = null) {
    companion object {
        val GLOBAL = SettingsQuery()
    }
}

/** Where a value came from: [source] is a file path, "profile <name>", "extension <id>" or "default". */
data class Provenance(val layer: LayerId, val language: String?, val source: String)

/** A resolved value, the layer that decided it, and the valid lower values it hid. */
data class Resolved<T>(val value: T, val winner: Provenance, val shadowed: List<Provenance>)

/** One layer of a stack, with the human-readable [source] used for provenance. */
data class Layer(val id: LayerId, val doc: LayerDoc, val source: String)

/** Receives values that failed validation, so the owner can log each once. */
fun interface InvalidValueSink {
    fun invalid(layer: LayerId, key: String, value: JsonElement)
}

/**
 * The resolution algorithm of LLD sec 3.2, pure: candidates are gathered high
 * to low (inside a layer the `[lang]` value before the plain one), values the
 * setting rejects are skipped - that is "invalid falls back to the next lower
 * layer" - and the default is the floor.
 */
object SettingsResolver {

    val DEFAULT_PROVENANCE = Provenance(LayerId.BUILT_IN, null, "default")

    fun <T> resolve(s: Setting<T>, layers: List<Layer>, language: String?, sink: InvalidValueSink): Resolved<T> {
        val valid = ArrayList<Pair<Provenance, JsonElement>>()
        for (layer in layers.asReversed()) {
            if (!s.scope.allows(layer.id) || !SettingsPolicy.layerMayHold(layer.id, s.key)) continue
            if (language != null && s.scope.languageOverridable) {
                layer.doc.value(s.key, language)?.let { consider(s, layer, language, it, valid, sink) }
            }
            layer.doc.plain[s.key]?.let { consider(s, layer, null, it, valid, sink) }
        }
        if (valid.isEmpty()) return Resolved(s.default, DEFAULT_PROVENANCE, emptyList())
        val winner = valid.first()
        val shadowed = valid.drop(1).map { it.first }
        if (s.merge == Merge.REPLACE) {
            return Resolved(requireNotNull(s.decode(winner.second)), winner.first, shadowed)
        }
        // Objects fold low -> high on top of the default, so a project entry
        // overrides one field of a user entry without restating the rest.
        val merged = valid.asReversed().fold(s.encode(s.default)) { acc, (_, v) -> mergeJson(acc, v, s.merge.depth) }
        val value = s.decode(merged) ?: requireNotNull(s.decode(winner.second))
        return Resolved(value, winner.first, shadowed)
    }

    /**
     * An undeclared key (an extension not installed yet, a typo): the highest raw
     * value, unvalidated, with the `[lang]` value first inside each layer.
     */
    fun raw(key: String, layers: List<Layer>, language: String?): Resolved<JsonElement>? {
        val found = ArrayList<Pair<Provenance, JsonElement>>()
        for (layer in layers.asReversed()) {
            if (!SettingsPolicy.layerMayHold(layer.id, key)) continue
            if (language != null) layer.doc.value(key, language)?.let { found += Provenance(layer.id, language, layer.source) to it }
            layer.doc.plain[key]?.let { found += Provenance(layer.id, null, layer.source) to it }
        }
        val top = found.firstOrNull() ?: return null
        return Resolved(top.second, top.first, found.drop(1).map { it.first })
    }

    /** Key-wise object merge [depth] levels deep; anything else is replaced by [high]. */
    fun mergeJson(low: JsonElement, high: JsonElement, depth: Int): JsonElement {
        if (depth <= 0 || low !is JsonObject || high !is JsonObject) return high
        val out = LinkedHashMap<String, JsonElement>(low)
        for ((k, v) in high) out[k] = out[k]?.let { mergeJson(it, v, depth - 1) } ?: v
        return JsonObject(out)
    }

    private fun <T> consider(
        s: Setting<T>, layer: Layer, language: String?, value: JsonElement,
        into: MutableList<Pair<Provenance, JsonElement>>, sink: InvalidValueSink,
    ) {
        if (s.isValid(value)) into += Provenance(layer.id, language, layer.source) to value
        else sink.invalid(layer.id, s.key, value)
    }
}

/**
 * Resolved settings for one (environment, project) at one moment: immutable, so
 * Compose reads it from a CompositionLocal and background code shares it
 * freely. Lookups are memoized per (key, language) for the life of the
 * snapshot; any layer change produces a new snapshot and so an empty memo.
 */
class SettingsSnapshot(
    val schema: SchemaState,
    /** Low to high, BUILT_IN excluded (defaults come from the schema). */
    val layers: List<Layer>,
    private val sink: InvalidValueSink = InvalidValueSink { _, _, _ -> },
) {
    private val memo = ConcurrentHashMap<Pair<String, String>, Resolved<*>>()

    operator fun <T> get(setting: Setting<T>): T = inspect(setting, null).value

    /** The value for a document in [languageId]: `[lang]` blocks apply to L-scope keys. */
    fun <T> get(setting: Setting<T>, languageId: String?): T = inspect(setting, languageId).value

    fun <T> inspect(setting: Setting<T>, languageId: String? = null): Resolved<T> {
        @Suppress("UNCHECKED_CAST") // memo entries are only ever stored under their own setting's key
        return memo.getOrPut(setting.key to languageId.orEmpty()) {
            SettingsResolver.resolve(setting, layers, languageId, sink)
        } as Resolved<T>
    }

    /** Any key, declared or not; declared keys go through validation and merging. */
    fun raw(key: String, languageId: String? = null): Resolved<JsonElement>? {
        val setting = schema.byKey[key] ?: return SettingsResolver.raw(key, layers, languageId)
        val resolved = inspect(setting, languageId)
        @Suppress("UNCHECKED_CAST")
        return Resolved((setting as Setting<Any?>).encode(resolved.value), resolved.winner, resolved.shadowed)
    }

    fun layer(id: LayerId): Layer? = layers.lastOrNull { it.id == id }

    /** True when [layer] holds any value (valid or not) for [setting] - what "reset" would remove. */
    fun isSetIn(setting: Setting<*>, layer: LayerId, language: String? = null): Boolean =
        layer(layer)?.doc?.value(setting.key, language) != null

    /** True when [layer] holds a value for [setting] that resolution skips. */
    fun isInvalidIn(setting: Setting<*>, layer: LayerId, language: String? = null): Boolean {
        val v = layer(layer)?.doc?.value(setting.key, language) ?: return false
        return !setting.isValid(v)
    }

    companion object {
        val DEFAULTS = SettingsSnapshot(SchemaState.builtInOnly(SettingsSchema.all), emptyList())
    }
}
