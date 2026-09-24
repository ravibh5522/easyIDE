package dev.easyide.app.extensions

import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.extensions.contrib.ContributionRegistry
import dev.easyide.extensions.contrib.ContributionSnapshot
import dev.easyide.extensions.contrib.Contributions
import dev.easyide.extensions.contrib.RegisteredExtension
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.ManifestParser
import dev.easyide.extensions.manifest.PackageFiles
import dev.easyide.extensions.manifest.ParseOptions
import dev.easyide.extensions.manifest.ParseResult
import dev.easyide.extensions.schema.ManifestSchema
import dev.easyide.extensions.whenclause.ContextLookup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.fail
import java.io.IOException

/** In-memory package for parser-driven adapter tests. */
class MemoryFiles(private val files: Map<String, String>, override val root: String = "/host/ext") : PackageFiles {
    override fun exists(path: String) = path in files
    override fun read(path: String): ByteArray = files[path]?.toByteArray() ?: throw IOException("missing $path")
    override fun list(): List<String> = files.keys.sorted()
    override fun size(path: String): Long = files[path]?.toByteArray()?.size?.toLong() ?: 0
    override fun hostPath(path: String) = "$root/$path"
}

object ExtFixtures {
    private val parser = ManifestParser(ManifestSchema.validator, ParseOptions(builtInCommands = CommandIds.ALL))

    fun descriptor(manifest: String, extra: Map<String, String> = emptyMap()): ExtensionDescriptor =
        when (val r = parser.parse(MemoryFiles(mapOf("package.json" to manifest) + extra))) {
            is ParseResult.Ok -> r.descriptor
            is ParseResult.Invalid -> { fail("invalid manifest: ${r.errors}"); error("unreachable") }
        }

    /** Registry snapshot of [descriptors] in order, on top of [builtIn]. */
    fun snapshot(vararg descriptors: ExtensionDescriptor, builtIn: Contributions = Contributions.EMPTY): ContributionSnapshot {
        val registry = ContributionRegistry(builtIn)
        registry.update(descriptors.map { RegisteredExtension(it.id, it.version, it.contributes) })
        return registry.snapshot.value
    }

    fun manifest(body: String, name: String = "demo"): String =
        """{ "name": "$name", "publisher": "acme", "version": "1.0.0", "engines": { "easyide": "^0.3.0" }, $body }"""

    fun context(vararg pairs: Pair<String, String>): ContextLookup {
        val map: Map<String, JsonElement> = pairs.associate { (k, v) -> k to Json.parseToJsonElement(v) }
        return ContextLookup { map[it] }
    }
}
