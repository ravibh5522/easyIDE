package dev.easyide.extensions.manifest

import dev.easyide.extensions.Fixtures
import dev.easyide.extensions.Manifests
import dev.easyide.extensions.MemoryPackage
import dev.easyide.extensions.action.Action
import dev.easyide.extensions.capability.Capability
import dev.easyide.extensions.contrib.KeyAction
import dev.easyide.extensions.schema.ManifestSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class ManifestParserTest {
    private fun fixture(name: String): PackageFiles {
        val dir = Fixtures.dir(name)
        return (PackageLayoutReader.read(dir, PackageLimits.DEFAULT) as PackageLayout.Ok).files
    }

    /** `#{x}` stands for `${x}` so manifests read naturally inside Kotlin strings. */
    private fun m(s: String) = s.replace("#{", "\${")

    private fun codes(manifest: String, extra: Map<String, String> = emptyMap()) = Manifests.errors(m(manifest), extra).map { it.code }

    private fun warnings(manifest: String, extra: Map<String, String> = emptyMap()) =
        Manifests.parse(m(manifest), extra).warnings.map { it.code }

    @Test fun `sdk-reference Python pack parses clean`() {
        val r = Manifests.parser.parse(fixture("python"))
        assertTrue(r.toString(), r is ParseResult.Ok)
        assertEquals(emptyList<Diagnostic>(), r.warnings)
        val d = (r as ParseResult.Ok).descriptor
        assertEquals("easyide.python", d.id.value)
        assertEquals(InstallScope.ENVIRONMENT, d.scope)
        assertEquals(setOf(Layer.L1), d.layers)
        assertTrue(d.capabilities.satisfies(Capability.Network(setOf("pypi.org"))))
        assertEquals(listOf(ActivationEvent.OnLanguage("python"), ActivationEvent.WorkspaceContains("**/pyproject.toml")), d.activationEvents)
        assertEquals("easyide.python/pyright", d.contributes.languageServers.first().key)
        assertEquals(listOf(".git"), d.contributes.languageServers[1].rootMarkers)
        assertEquals(KeyAction.Insert(":"), d.contributes.keyRows.single().keys.first().action)
        assertEquals(KeyAction.Command("python.runFile"), d.contributes.keyRows.single().keys.last().action)
        val seq = d.actions["python.selectVenv"] as Action.Sequence
        assertEquals("venvs", seq.steps.first().bindAs)
        assertEquals("/opt/easyide/extensions/easyide.python", d.guestRoot)
        assertEquals("navigation", d.contributes.menus.first { it.menuId == "editor/title" }.group)
        assertEquals(1.0, d.contributes.menus.first { it.menuId == "editor/title" }.order)
        assertEquals("python", d.contributes.languageConfigurations.single().language)
        assertEquals(listOf("editor.tabSize", "editor.formatOnSave"), d.contributes.configurationDefaults.map { it.key })
        assertEquals("python", d.contributes.configurationDefaults.first().language)
    }

    @Test fun `sdk-reference theme pack is global with no capabilities`() {
        val d = (Manifests.parser.parse(fixture("theme")) as ParseResult.Ok).descriptor
        assertEquals(InstallScope.GLOBAL, d.scope)
        assertTrue(d.capabilities.items.isEmpty())
        assertEquals("Graphite Night", d.contributes.themes.single().label)
    }

    @Test fun `phase 1 and 2 - missing, non-UTF-8, comments and syntax errors`() {
        assertEquals(DiagnosticCode.MANIFEST_MISSING, (Manifests.parser.parse(MemoryPackage(emptyMap())) as ParseResult.Invalid).errors.single().code)
        val latin1 = object : PackageFiles by MemoryPackage(mapOf("package.json" to "")) {
            override fun read(path: String) = byteArrayOf(0x7b, 0xff.toByte(), 0x7d)
        }
        assertEquals(DiagnosticCode.MANIFEST_ENCODING, (Manifests.parser.parse(latin1) as ParseResult.Invalid).errors.single().code)
        val comment = Manifests.errors("{ // no\n \"name\": \"a\" }").single()
        assertEquals(DiagnosticCode.JSON_SYNTAX, comment.code)
        assertTrue(comment.message, comment.message.startsWith("line "))
        assertEquals(listOf(DiagnosticCode.JSON_SYNTAX), codes("""{ "name": "a", }"""))
    }

    @Test fun `manifest over the file limit is refused`() {
        val options = ParseOptions(limits = PackageLimits.DEFAULT.copy(fileBytes = 10))
        assertEquals(DiagnosticCode.PACKAGE_FILE_TOO_LARGE, Manifests.errors(Manifests.minimal(), options = options).single().code)
    }

    @Test fun `phase 3 - nls resolves locale, then default, keeps literal when missing`() {
        val manifest = Manifests.minimal(""""displayName": "%name%", "description": "%desc%", "license": "%nope%" """)
        val extra = mapOf(
            "l10n/package.nls.json" to """{ "name": "Base", "desc": {"message": "Default description"} }""",
            "l10n/package.nls.de.json" to """{ "name": "Deutsch" }""",
        )
        val de = Manifests.ok(manifest, extra, ParseOptions(locale = Locale.GERMANY))
        assertEquals("Deutsch", de.displayName)
        assertEquals("Default description", de.description)
        assertEquals("%nope%", de.license)
        assertEquals("Base", Manifests.ok(manifest, extra).displayName)
        assertTrue(DiagnosticCode.NLS_MISSING in Manifests.parse(manifest, extra).warnings.map { it.code })
        // Bundle lookup order: NlsTest in :extension-schema (Nls is internal there).
    }

    @Test fun `phase 4 - schema errors are path precise`() {
        val e = Manifests.errors("""{ "name": "Bad Name", "publisher": "acme", "version": "1.0", "engines": {} }""")
        assertEquals(setOf("/name", "/version", "/engines/easyide"), e.map { it.pointer }.toSet())
        assertTrue(e.all { it.code == DiagnosticCode.SCHEMA })
        val action = Manifests.errors(Manifests.minimal("""
            "contributes": { "commands": [{ "command": "demo.x", "title": "X" }] },
            "easyide": { "capabilities": ["sandbox.exec"], "actions": { "demo.x": { "type": "sandboxExec", "command": "ls", "output": "capture" } } }"""))
        assertEquals("/easyide/actions/demo.x/command", action.single().pointer)
        val unknownType = Manifests.errors(Manifests.minimal("""
            "contributes": { "commands": [{ "command": "demo.x", "title": "X" }] },
            "easyide": { "actions": { "demo.x": { "type": "launchRockets" } } }"""))
        assertEquals("/easyide/actions/demo.x", unknownType.single().pointer)
    }

    @Test fun `unknown keys and contribution points warn, never fail (R-API-08)`() {
        val w = warnings(Manifests.minimal(""""main": "./out/ext.js", "contributes": { "debuggers": [] }, "engines2": 1"""))
        assertEquals(3, w.count { it == DiagnosticCode.UNKNOWN_KEY })
    }

    @Test fun `phase 5 - engines range must parse and contain the API version`() {
        assertEquals(listOf(DiagnosticCode.ENGINE_RANGE), codes(Manifests.minimal().replace("^0.3.0", "not a range")))
        val mismatch = Manifests.errors(Manifests.minimal().replace("^0.3.0", "^1.0.0")).single()
        assertEquals(DiagnosticCode.ENGINE_MISMATCH, mismatch.code)
        assertTrue(mismatch.message.contains("^1.0.0") && mismatch.message.contains("0.3.0"))
        Manifests.ok(Manifests.minimal().replace("^0.3.0", "^1.0.0"), options = ParseOptions(apiVersion = SemVer(1, 2, 0)))
    }

    @Test fun `phase 6 - file references are relative, inside and present`() {
        assertEquals(listOf(DiagnosticCode.PATH_INVALID), codes(Manifests.minimal(""""icon": "../icon.png""""), mapOf("icon.png" to "x")))
        assertEquals(listOf(DiagnosticCode.PATH_INVALID), codes(Manifests.minimal(""""icon": "/etc/passwd"""")))
        assertEquals(listOf(DiagnosticCode.PATH_MISSING), codes(Manifests.minimal(""""icon": "./missing.png"""")))
        val w = warnings(Manifests.minimal(), mapOf("stray.bin" to "x", "README.md" to "r", "LICENSE" to "l"))
        assertEquals(listOf(DiagnosticCode.FILE_UNREFERENCED), w)
    }

    @Test fun `phase 7 - cross references`() {
        val base = """"contributes": { "commands": [{ "command": "demo.run", "title": "Run" }], "menus": { "editor/title": [{ "command": "%s" }] } }"""
        Manifests.ok(Manifests.minimal(base.format("demo.run")))
        assertEquals(listOf(DiagnosticCode.COMMAND_UNRESOLVED), codes(Manifests.minimal(base.format("demo.missing"))))
        assertTrue(DiagnosticCode.COMMAND_UNKNOWN in warnings(Manifests.minimal(base.format("workbench.action.future"))))
        Manifests.ok(Manifests.minimal(base.format("workbench.action.files.save")),
            options = ParseOptions(builtInCommands = setOf("workbench.action.files.save")))
        assertEquals(listOf(DiagnosticCode.ACTION_NOT_COMMAND), codes(Manifests.minimal("""
            "easyide": { "actions": { "demo.ghost": { "type": "showMessage", "text": "hi" } } }""")))
        assertEquals(listOf(DiagnosticCode.DUPLICATE_ID), codes(Manifests.minimal("""
            "contributes": { "commands": [{ "command": "demo.a", "title": "A" }, { "command": "demo.a", "title": "B" }] }""")))
        assertTrue(DiagnosticCode.MENU_UNKNOWN in warnings(Manifests.minimal("""
            "contributes": { "commands": [{ "command": "demo.a", "title": "A" }], "menus": { "debug/toolbar": [{ "command": "demo.a" }] } }""")))
        assertTrue(DiagnosticCode.ID_PREFIX in warnings(Manifests.minimal("""
            "contributes": { "commands": [{ "command": "other.a", "title": "A" }] }""")))
    }

    @Test fun `key row keys take at most one action and longPress exactly one`() {
        fun row(key: String) = Manifests.minimal(""""easyide": { "keyRows": [{ "id": "demo.row", "title": "R", "keys": [$key] }] }""")
        assertEquals(KeyAction.Insert("x"), Manifests.ok(row("""{ "label": "x" }""")).contributes.keyRows.single().keys.single().action)
        assertEquals(listOf(DiagnosticCode.KEY_ROW_KEY), codes(row("""{ "label": "x", "insert": "a", "key": "tab" }""")))
        assertEquals(listOf(DiagnosticCode.KEY_ROW_KEY), codes(row("""{ "label": "x", "longPress": {} }""")))
        val lp = Manifests.ok(row("""{ "label": "(", "longPress": { "snippet": "($0)" } }""")).contributes.keyRows.single().keys.single()
        assertEquals(KeyAction.Snippet("($0)"), lp.longPress)
    }

    @Test fun `phase 8 - when-clauses parse, unknown keys warn`() {
        val bad = Manifests.errors(Manifests.minimal("""
            "contributes": { "commands": [{ "command": "demo.a", "title": "A", "enablement": "a &&" }] }""")).single()
        assertEquals(DiagnosticCode.WHEN_SYNTAX, bad.code)
        assertEquals("/contributes/commands/0/enablement", bad.pointer)
        assertTrue(bad.message.startsWith("column 5"))
        assertTrue(DiagnosticCode.WHEN_UNKNOWN_KEY in warnings(Manifests.minimal("""
            "contributes": { "commands": [{ "command": "demo.a", "title": "A", "enablement": "myOwnKey" }] }""")))
    }

    @Test fun `templates - malformed and unknown variables, unbound inputs and results`() {
        fun action(json: String) = Manifests.minimal("""
            "contributes": { "commands": [{ "command": "demo.a", "title": "A" }] },
            "easyide": { "capabilities": ["sandbox.exec"], "actions": { "demo.a": $json } }""")
        assertEquals(listOf(DiagnosticCode.TEMPLATE), codes(action("""{ "type": "runInTerminal", "command": "echo #{file" }""")))
        assertEquals(listOf(DiagnosticCode.TEMPLATE), codes(action("""{ "type": "runInTerminal", "command": "echo #{nope}" }""")))
        assertEquals(listOf(DiagnosticCode.TEMPLATE), codes(action("""{ "type": "runInTerminal", "command": "echo #{input:who}" }""")))
        assertEquals(listOf(DiagnosticCode.TEMPLATE), codes(action("""{ "type": "runInTerminal", "command": "echo #{result:out}" }""")))
        assertEquals(listOf(DiagnosticCode.ENV_NAME), codes(action("""{ "type": "runInTerminal", "command": "x", "env": { "A;rm": "1" } }""")))
        assertEquals(listOf(DiagnosticCode.REGEX), codes(action("""{ "type": "showInputBox", "id": "v", "validate": "(" }""")))
        assertEquals(listOf(DiagnosticCode.ONE_OF), codes(action("""{ "type": "showQuickPick", "id": "v" }""")))
        Manifests.ok(action("""{ "type": "runInTerminal", "command": "echo #{env:HOME} #{config:demo.x} #{fileBasename} $ {x}" }"""))
    }

    @Test fun `phase 9 - capability audit refuses undeclared needs at load (R-SEC-08)`() {
        fun pack(caps: String, action: String) = Manifests.minimal("""
            "contributes": { "commands": [{ "command": "demo.a", "title": "A" }] },
            "easyide": { "capabilities": [$caps], "actions": { "demo.a": $action } }""")
        val exec = """{ "type": "sandboxExec", "command": ["ls"], "output": "capture" }"""
        val undeclared = Manifests.errors(m(pack("", exec))).single()
        assertEquals(DiagnosticCode.CAP_UNDECLARED, undeclared.code)
        assertEquals("/easyide/actions/demo.a", undeclared.pointer)
        Manifests.ok(pack("\"sandbox.exec\"", exec))
        val seq = """{ "type": "sequence", "steps": [ { "type": "openFile", "path": "a" }, { "type": "applyEdit", "edits": [{ "range": { "start": {"line":0,"character":0}, "end": {"line":0,"character":0} }, "text": "x" }] } ] }"""
        assertEquals(listOf(DiagnosticCode.CAP_UNDECLARED), codes(pack("\"fs.project(read)\"", seq)))
        Manifests.ok(pack("\"fs.project(write)\"", seq))
        assertEquals(listOf(DiagnosticCode.CAP_SYNTAX), codes(pack("\"root\"", """{ "type": "showMessage", "text": "x" }""")))
        assertEquals(listOf(DiagnosticCode.CAP_SYNTAX), codes(pack("\"network(bad host)\"", """{ "type": "showMessage", "text": "x" }""")))
        assertTrue(DiagnosticCode.CAP_UNUSED in warnings(pack("\"lsp.request\"", """{ "type": "showMessage", "text": "x" }""")))
        val servers = Manifests.errors(Manifests.minimal("""
            "easyide": { "languageServers": [{ "id": "s", "languages": ["x"], "command": ["srv"] }] }"""))
        assertEquals(listOf(DiagnosticCode.CAP_UNDECLARED), servers.map { it.code })
        val stage = """{ "type": "revealStage", "stage": "bottom" }"""
        Manifests.ok(pack("", stage))
        assertEquals(listOf(DiagnosticCode.CAP_UNDECLARED), codes(pack("", """{ "type": "revealStage", "stage": "demo.panel" }""")))
    }

    @Test fun `phase 10 - global scope with environment use is refused`() {
        val pack = Manifests.minimal("""
            "contributes": { "commands": [{ "command": "demo.a", "title": "A" }] },
            "easyide": { "scope": "global", "capabilities": ["sandbox.exec"],
                         "actions": { "demo.a": { "type": "runInTerminal", "command": "make" } } }""")
        assertEquals(listOf(DiagnosticCode.SCOPE), codes(pack))
        assertEquals(InstallScope.ENVIRONMENT, Manifests.ok(pack.replace("\"scope\": \"global\", ", "")).scope)
    }

    @Test fun `phase 11 - content files must load`() {
        val theme = Manifests.minimal(""""contributes": { "themes": [{ "label": "T", "uiTheme": "vs-dark", "path": "./t.json" }] }""")
        assertEquals(listOf(DiagnosticCode.CONTENT), codes(theme, mapOf("t.json" to "{ nope")))
        assertEquals(listOf(DiagnosticCode.CONTENT), codes(theme, mapOf("t.json" to """{ "colors": [] }""")))
        Manifests.ok(theme, mapOf("t.json" to "{ /* jsonc */ \"colors\": {}, }"))
        val snippets = Manifests.minimal(""""contributes": { "snippets": [{ "language": "x", "path": "s.json" }] }""")
        assertEquals(listOf(DiagnosticCode.CONTENT), codes(snippets, mapOf("s.json" to """{ "a": { "prefix": "p" } }""")))
        val icons = Manifests.minimal(""""contributes": { "iconThemes": [{ "id": "demo.icons", "label": "I", "path": "icons/theme.json" }] }""")
        Manifests.ok(icons, mapOf("icons/theme.json" to """{ "iconDefinitions": { "f": { "iconPath": "./f.svg" } } }""", "icons/f.svg" to "<svg/>"))
        assertEquals(listOf(DiagnosticCode.CONTENT), codes(icons, mapOf("icons/theme.json" to """{ "iconDefinitions": { "f": { "iconPath": "./gone.svg" } } }""")))
    }

    @Test fun `phase 12 - star activation and engines vscode warn`() {
        val d = Manifests.parse(Manifests.minimal(""""activationEvents": ["*", "onDebug"]""").replace("^0.3.0\" }", "^0.3.0\", \"vscode\": \"^1.80.0\" }"))
        val w = d.warnings.map { it.code }
        assertTrue(w.containsAll(listOf(DiagnosticCode.ACTIVATION_STAR, DiagnosticCode.ACTIVATION_EVENT, DiagnosticCode.ENGINES_VSCODE)))
        assertEquals(listOf(ActivationEvent.OnStartupFinished), (d as ParseResult.Ok).descriptor.activationEvents)
    }

    @Test fun `contributed setting defaults are checked against their own schema`() {
        val d = Manifests.parse(Manifests.minimal(""""contributes": { "configuration": { "title": "Demo", "properties": {
            "demo.n": { "type": "integer", "default": "four", "minimum": 1 },
            "demo.ok": { "type": "boolean", "default": true, "scope": "language-overridable", "format": "x" } } } }"""))
        val desc = (d as ParseResult.Ok).descriptor
        assertNull(desc.contributes.configuration.first { it.key == "demo.n" }.default)
        assertEquals(2, d.warnings.count { it.code == DiagnosticCode.CONTENT_IGNORED })
    }

    @Test fun `wasm module size is limited`() {
        val pack = Manifests.minimal(""""easyide": { "wasm": { "module": "wasm/main.wasm", "abi": 1 } }""")
        val big = mapOf("wasm/main.wasm" to "x".repeat(64))
        assertEquals(setOf(Layer.L2), Manifests.ok(pack, big).layers)
        val tight = ParseOptions(limits = PackageLimits.DEFAULT.copy(wasmModuleBytes = 10))
        assertEquals(listOf(DiagnosticCode.WASM_TOO_LARGE), Manifests.errors(pack, big, tight).map { it.code })
    }

    @Test fun `schema validator instance is shared and cached`() {
        assertTrue(ManifestSchema.validator === ManifestSchema.validator)
    }
}
