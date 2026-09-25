package dev.easyide.app.extensions.install

import dev.easyide.app.extensions.BuiltInPackFixtures
import dev.easyide.app.extensions.adapters.IconThemeFile
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.extensions.manifest.ManifestParser
import dev.easyide.extensions.manifest.PackageLayout
import dev.easyide.extensions.manifest.PackageLayoutReader
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.ParseOptions
import dev.easyide.extensions.manifest.ParseResult
import dev.easyide.extensions.schema.ManifestSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A `.vsix`-shaped icon theme extension (as Open VSX serves it) installs through the same checks as an easyIDE pack. */
class VsCodeIconThemeAdapterTest {

    @get:Rule val tmp = TemporaryFolder()

    private val parser = ManifestParser(ManifestSchema.validator, ParseOptions(builtInCommands = CommandIds.ALL))

    private fun write(dir: File, path: String, text: String) = File(dir, path).also { it.parentFile.mkdirs() }.writeText(text)

    /** Layout like a real vsix: envelope files at the top, the extension under `extension/`, icons a step up from the theme file. */
    private fun vsix(theme: String, manifestExtras: String = ""): File {
        val dir = tmp.newFolder()
        write(dir, "[Content_Types].xml", "<Types/>")
        write(dir, "extension.vsixmanifest", "<PackageManifest/>")
        write(
            dir, "extension/package.json",
            """{ "name": "fancy-icons", "publisher": "acme", "version": "1.2.3", "displayName": "Fancy Icons", "description": "d", "license": "MIT",
                "engines": { "vscode": "^1.60.0" }, "main": "./dist/extension.js", "activationEvents": ["*"],
                "contributes": { "commands": [{ "command": "x.y", "title": "Y" }],
                  "iconThemes": [{ "id": "fancy", "label": "Fancy", "path": "./dist/theme.json" }] } $manifestExtras }""",
        )
        write(dir, "extension/dist/theme.json", theme)
        write(dir, "extension/icons/py.svg", "<svg viewBox='0 0 16 16'><path d='M0 0h8v8z' fill='#36c'/></svg>")
        write(dir, "extension/icons/file.svg", "<svg viewBox='0 0 16 16'><path d='M0 0h8v8z' fill='#999'/></svg>")
        write(dir, "extension/icons/py_light.svg", "<svg viewBox='0 0 16 16'><path d='M0 0h8v8z' fill='#124'/></svg>")
        return dir
    }

    private fun parse(dir: File): ParseResult = when (val layout = PackageLayoutReader.read(dir, PackageLimits.DEFAULT)) {
        is PackageLayout.Invalid -> error("layout: ${layout.errors}")
        is PackageLayout.Ok -> parser.parse(layout.files)
    }

    private val svgTheme = """{
        "iconDefinitions": { "py": { "iconPath": "./../icons/py.svg" }, "file": { "iconPath": "../icons/file.svg" }, "py-l": { "iconPath": "./../icons/py_light.svg" } },
        "file": "file", "fileExtensions": { "py": "py" }, "light": { "fileExtensions": { "py": "py-l" } },
        "highContrast": { "fileExtensions": { "py": "py" } }
    }"""

    @Test fun `a vsix with an icon theme becomes an installable pack whose theme loads with parent-folder paths and light overrides`() {
        val dir = vsix(svgTheme)
        assertTrue(VsCodeIconThemeAdapter.adapt(dir))
        assertFalse(File(dir, "extension").exists())
        assertFalse(File(dir, "extension.vsixmanifest").exists())
        val ok = parse(dir) as ParseResult.Ok
        assertEquals("acme.fancy-icons", ok.descriptor.id.value)
        val contribution = ok.descriptor.contributes.iconThemes.single()
        assertEquals("fancy", contribution.id)
        assertEquals(emptyList<Any>(), ok.descriptor.contributes.commands)

        val themeFile = File(dir, contribution.file.path)
        val theme = IconThemeFile.parse("fancy", themeFile.readText(), themeFile, dir)!!
        assertEquals(setOf("py", "file", "py-l"), theme.icons.keys)
        assertTrue(theme.fileIcon("a.py", null, light = false)!!.endsWith("icons/py.svg"))
        assertTrue(theme.fileIcon("a.py", null, light = true)!!.endsWith("icons/py_light.svg"))
        assertTrue(theme.fileIcon("a.zzz", null, light = false)!!.endsWith("icons/file.svg"))
    }

    @Test fun `an icon path that climbs out of the package is refused, not resolved`() {
        val dir = vsix("""{ "iconDefinitions": { "e": { "iconPath": "../../../etc/x.svg" } } }""")
        VsCodeIconThemeAdapter.adapt(dir)
        assertTrue(parse(dir) is ParseResult.Invalid)
    }

    @Test fun `a font-based theme loads with no icons and says why through its font count`() {
        val dir = vsix(
            """{ "fonts": [{ "id": "f", "src": [{ "path": "./f.woff", "format": "woff" }] }],
                "iconDefinitions": { "a": { "fontCharacter": "\\E001", "fontColor": "#fff" }, "b": { "fontCharacter": "\\E002" } },
                "fileExtensions": { "py": "a" } }""",
        )
        assertTrue(VsCodeIconThemeAdapter.adapt(dir))
        val ok = parse(dir) as ParseResult.Ok
        assertTrue("icon fonts warning", ok.warnings.any { it.message.contains("icon fonts are not supported") })
        val themeFile = File(dir, ok.descriptor.contributes.iconThemes.single().file.path)
        val theme = IconThemeFile.parse("fancy", themeFile.readText(), themeFile, dir)!!
        assertTrue(theme.icons.isEmpty())
        assertEquals(2, theme.fontIcons)
    }

    @Test fun `easyIDE packs and vscode extensions without icon themes are left alone`() {
        val own = tmp.newFolder()
        write(own, "package.json", """{ "name": "a", "publisher": "b", "version": "1.0.0", "engines": { "easyide": "^0.3.0", "vscode": "^1.0.0" }, "contributes": { "iconThemes": [{ "id": "i", "label": "I", "path": "t.json" }] } }""")
        assertFalse(VsCodeIconThemeAdapter.adapt(own))
        val noIcons = tmp.newFolder()
        write(noIcons, "package.json", """{ "name": "a", "publisher": "b", "version": "1.0.0", "engines": { "vscode": "^1.0.0" }, "contributes": { "themes": [] } }""")
        assertFalse(VsCodeIconThemeAdapter.adapt(noIcons))
        assertFalse(VsCodeIconThemeAdapter.adapt(tmp.newFolder()))
        val broken = tmp.newFolder()
        write(broken, "package.json", "{ nope")
        assertFalse(VsCodeIconThemeAdapter.adapt(broken))
    }

    @Test fun `the vendored Material Icon Theme survives the same conversion`() {
        val dir = tmp.newFolder()
        BuiltInPackFixtures.root.resolve("easyide.material-icons").copyRecursively(File(dir, "extension"))
        File(dir, "extension/package.json").writeText(
            """{ "name": "Material-Icon-Theme", "publisher": "PKief", "version": "5.38.1", "engines": { "vscode": "^1.80.0" },
                "contributes": { "iconThemes": [{ "id": "material-icon-theme", "label": "Material Icon Theme", "path": "./dist/material-icons.json" }] } }""",
        )
        assertTrue(VsCodeIconThemeAdapter.adapt(dir))
        val ok = parse(dir)
        assertTrue(ok.toString().take(300), ok is ParseResult.Ok)
        assertEquals("pkief.material-icon-theme", (ok as ParseResult.Ok).descriptor.id.value)
    }
}
