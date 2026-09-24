package dev.easyide.extensions

import dev.easyide.extensions.manifest.Diagnostic
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.ManifestParser
import dev.easyide.extensions.manifest.PackageFiles
import dev.easyide.extensions.manifest.ParseOptions
import dev.easyide.extensions.manifest.ParseResult
import dev.easyide.extensions.schema.ManifestSchema
import dev.easyide.extensions.settings.ConfigTarget
import dev.easyide.extensions.settings.SettingsPort
import dev.easyide.extensions.settings.SettingsQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.fail
import java.io.IOException

/** Settings as a flat map (plus optional per-language values); records writes. */
class FakeSettings(initial: Map<String, JsonElement> = emptyMap()) : SettingsPort {
    val values = HashMap(initial)
    val languageValues = HashMap<Pair<String, String>, JsonElement>()
    var profile: Set<String>? = null
    val writes = ArrayList<Triple<String, JsonElement?, ConfigTarget>>()
    val reads = ArrayList<String>()
    private val versionFlow = MutableStateFlow(0L)
    override val version: StateFlow<Long> get() = versionFlow

    override fun value(key: String, query: SettingsQuery): JsonElement? {
        reads += key
        return query.languageId?.let { languageValues[it to key] } ?: values[key]
    }

    override fun profileExtensions(): Set<String>? = profile

    override suspend fun write(key: String, value: JsonElement?, target: ConfigTarget, query: SettingsQuery) {
        writes += Triple(key, value, target)
        if (value == null) values.remove(key) else values[key] = value
        bump()
    }

    fun set(key: String, json: String) { values[key] = Json.parseToJsonElement(json); bump() }
    fun bump() { versionFlow.value++ }
}

/** An in-memory package: path -> content. */
class MemoryPackage(private val files: Map<String, String>, override val root: String = "/host/ext") : PackageFiles {
    override fun exists(path: String) = path in files
    override fun read(path: String): ByteArray = files[path]?.toByteArray() ?: throw IOException("missing $path")
    override fun list(): List<String> = files.keys.sorted()
    override fun size(path: String): Long = files[path]?.toByteArray()?.size?.toLong() ?: 0
    override fun hostPath(path: String) = "$root/$path"
}

object Manifests {
    val parser = ManifestParser(ManifestSchema.validator)

    fun parse(manifest: String, extra: Map<String, String> = emptyMap(), options: ParseOptions = ParseOptions()): ParseResult =
        ManifestParser(ManifestSchema.validator, options).parse(MemoryPackage(mapOf("package.json" to manifest) + extra))

    fun ok(manifest: String, extra: Map<String, String> = emptyMap(), options: ParseOptions = ParseOptions()): ExtensionDescriptor =
        when (val r = parse(manifest, extra, options)) {
            is ParseResult.Ok -> r.descriptor
            is ParseResult.Invalid -> { fail("expected valid manifest, got ${r.errors}"); error("unreachable") }
        }

    fun errors(manifest: String, extra: Map<String, String> = emptyMap(), options: ParseOptions = ParseOptions()): List<Diagnostic> =
        when (val r = parse(manifest, extra, options)) {
            is ParseResult.Invalid -> r.errors
            is ParseResult.Ok -> { fail("expected errors, manifest was valid"); error("unreachable") }
        }

    /** A minimal valid manifest with [body] spliced into the top-level object. */
    fun minimal(body: String = "", name: String = "demo", publisher: String = "acme"): String =
        """{ "name": "$name", "publisher": "$publisher", "version": "1.0.0", "engines": { "easyide": "^0.3.0" }${if (body.isBlank()) "" else ", $body"} }"""
}
