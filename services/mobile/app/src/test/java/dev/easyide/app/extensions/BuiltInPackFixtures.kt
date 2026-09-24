package dev.easyide.app.extensions

import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.app.ui.screens.workspace.TerminalKeyboard
import dev.easyide.extensions.contrib.CommandContribution
import dev.easyide.extensions.contrib.CommandIcon
import dev.easyide.extensions.contrib.ContributionRegistry
import dev.easyide.extensions.contrib.ContributionSnapshot
import dev.easyide.extensions.contrib.Contributions
import dev.easyide.extensions.contrib.RegisteredExtension
import dev.easyide.extensions.manifest.ManifestParser
import dev.easyide.extensions.manifest.PackageLayout
import dev.easyide.extensions.manifest.PackageLayoutReader
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.ParseOptions
import dev.easyide.extensions.manifest.ParseResult
import dev.easyide.extensions.schema.ManifestSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.fail
import java.io.File

/**
 * The APK's built-in packs read the way the app reads them (layout reader, then the parser
 * with the app's built-in command ids), plus the registry the app builds over them.
 */
object BuiltInPackFixtures {
    val root = File("src/main/assets/extensions")
    private val parser = ManifestParser(ManifestSchema.validator, ParseOptions(builtInCommands = CommandIds.ALL))

    fun ids(): List<String> = root.listFiles()!!.filter { it.isDirectory }.map { it.name }.sorted()

    fun load(id: String): ParseResult.Ok = when (val layout = PackageLayoutReader.read(File(root, id), PackageLimits.DEFAULT)) {
        is PackageLayout.Invalid -> { fail("$id layout: ${layout.errors}"); error("unreachable") }
        is PackageLayout.Ok -> when (val r = parser.parse(layout.files)) {
            is ParseResult.Invalid -> { fail("$id: ${r.errors}"); error("unreachable") }
            is ParseResult.Ok -> r
        }
    }

    /** The raw manifest, for checks over the author's text (every `when` string, say). */
    fun manifest(id: String): JsonObject = Json.parseToJsonElement(File(root, "$id/package.json").readText()).jsonObject

    /** Built-in contributions as ExtensionsContainer registers them; titles are the ids (no resources on the JVM). */
    val builtIns = Contributions(
        commands = BuiltInCommandTable.ALL.map { c ->
            CommandContribution(c.id, c.id, null, c.shortTitle?.let { c.id }, c.icon?.let(CommandIcon::Token), null)
        },
        keyRows = listOf(TerminalKeyboard.row("Terminal")),
    )

    /** Every built-in pack enabled, in id order, on top of [builtIns]. */
    fun snapshot(): ContributionSnapshot {
        val registry = ContributionRegistry(builtIns)
        registry.update(ids().map { load(it).descriptor }.map { RegisteredExtension(it.id, it.version, it.contributes) })
        return registry.snapshot.value
    }
}
