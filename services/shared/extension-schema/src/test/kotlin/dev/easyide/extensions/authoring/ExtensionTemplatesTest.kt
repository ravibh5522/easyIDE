package dev.easyide.extensions.authoring

import dev.easyide.extensions.AppApi
import dev.easyide.extensions.manifest.ManifestParser
import dev.easyide.extensions.manifest.PackageLayout
import dev.easyide.extensions.manifest.PackageLayoutReader
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.ParseOptions
import dev.easyide.extensions.manifest.ParseResult
import dev.easyide.extensions.schema.BuiltInCommands
import dev.easyide.extensions.schema.ManifestSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExtensionTemplatesTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The one copy of the templates, shared with the CLI jar and the APK assets. */
    private val templates = File("../extension-templates/templates")

    private fun reader(template: String): (String) -> String? = { rel -> File(templates, "$template/$rel").takeIf { it.isFile }?.readText() }

    private val vars = ExtensionTemplates.variables("acme", "graphite-night", null, 2026)!!

    @Test fun `variables lowercase, default the display name and derive crate and engine`() {
        val v = ExtensionTemplates.variables(" Acme ", "Graphite-Night", " ", 2026)!!
        assertEquals("acme", v["publisher"])
        assertEquals("graphite-night", v["name"])
        assertEquals("Graphite Night", v["displayName"])
        assertEquals("graphite_night", v["crate"])
        assertEquals("^${AppApi.VERSION}", v["engine"])
        assertEquals("2026", v["year"])
        assertEquals("Mine", ExtensionTemplates.variables("acme", "x", "Mine", 2026)!!["displayName"])
    }

    @Test fun `an invalid publisher or name gives no variables`() {
        assertNull(ExtensionTemplates.variables("ac me", "x", null, 2026))
        assertNull(ExtensionTemplates.variables("acme", "-x", null, 2026))
        assertNull(ExtensionTemplates.variables("acme", "", null, 2026))
    }

    @Test fun `dotfile segments are stored with the dot- prefix`() {
        assertEquals("dot-gitignore", ExtensionTemplates.storedPath(".gitignore"))
        assertEquals("a/dot-cfg/b.json", ExtensionTemplates.storedPath("a/.cfg/b.json"))
        assertEquals("themes/{{name}}-color-theme.json", ExtensionTemplates.storedPath("themes/{{name}}-color-theme.json"))
    }

    @Test fun `render substitutes paths and contents`() {
        val files = ExtensionTemplates.render("t", vars) { rel ->
            mapOf("files.txt" to "package.json\n\n .gitignore \nthemes/{{name}}.json\n", "package.json" to "{\"name\":\"{{name}}\",\"e\":\"{{engine}}\"}",
                "dot-gitignore" to "dist/", "themes/{{name}}.json" to "{{displayName}} {{year}}")[rel]
        }
        assertEquals(listOf("package.json", ".gitignore", "themes/graphite-night.json"), files.map { it.path })
        assertEquals("{\"name\":\"graphite-night\",\"e\":\"^${AppApi.VERSION}\"}", files[0].text)
        assertEquals("Graphite Night 2026", files[2].text)
    }

    @Test(expected = IllegalStateException::class)
    fun `a listed path escaping the folder is refused`() {
        ExtensionTemplates.render("t", vars) { rel -> mapOf("files.txt" to "../x", "../x" to "")[rel] }
    }

    @Test(expected = IllegalStateException::class)
    fun `a missing file is a defect`() {
        ExtensionTemplates.render("t", vars) { rel -> mapOf("files.txt" to "package.json")[rel] }
    }

    @Test fun `every template directory is known and every declarative template parses as generated`() {
        assertEquals(ExtensionTemplates.ALL.toSet(), templates.list()!!.toSet())
        val parser = ManifestParser(ManifestSchema.validator, ParseOptions(builtInCommands = BuiltInCommands.IDS))
        for (t in ExtensionTemplates.DECLARATIVE) {
            val out = tmp.newFolder(t)
            ExtensionTemplates.render(t, vars, reader(t)).forEach { f -> File(out, f.path).apply { parentFile.mkdirs(); writeText(f.text) } }
            val rules = PackageIgnores.rules(null)
            val layout = PackageLayoutReader.read(out, PackageLimits.DEFAULT) { rules.ignored(it) }
            val files = (layout as? PackageLayout.Ok ?: error("$t: $layout")).files
            assertFalse("$t ships test/", files.list().any { it.startsWith("test/") })
            val r = parser.parse(files)
            assertTrue("$t: $r", r is ParseResult.Ok)
            assertEquals("acme.graphite-night", (r as ParseResult.Ok).descriptor.id.value)
        }
    }

    @Test fun `default ignores keep package content and drop author files`() {
        val rules = PackageIgnores.rules("# local\nnotes/\n!keep.key")
        assertTrue(listOf(".gitignore", ".easyide-ext.json", "test/", "test/a.json", "dist/x.easyext", "a.key", "notes/", "notes/a.md").all(rules::ignored))
        assertFalse(listOf("package.json", "themes/", "themes/a.json", "keep.key", "testing/a.json").any(rules::ignored))
    }
}
