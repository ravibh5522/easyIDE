package dev.easyide.app.extensions.adapters

import dev.easyide.app.extensions.ExtFixtures
import dev.easyide.extensions.action.EditorState
import dev.easyide.extensions.action.WorkspaceState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguagesTasksStatusTest {

    private val pack = ExtFixtures.descriptor(ExtFixtures.manifest("""
        "contributes": {
          "languages": [
            { "id": "zig", "extensions": [".zig"], "filenames": ["build.zig.zon"], "configuration": "./lc.json" },
            { "id": "tpl", "extensions": [".tpl.html"], "filenamePatterns": ["*.tmpl"], "firstLine": "^#!.*tplrun" }
          ],
          "grammars": [ { "language": "zig", "scopeName": "source.zig", "path": "./zig.tmLanguage.json" } ]
        },
        "easyide": { "statusBarItems": [
          { "id": "demo.interp", "text": "Py ${'$'}{config:demo.interpreter}", "alignment": "right", "priority": 1 },
          { "id": "demo.file", "text": "${'$'}{fileBasename}:${'$'}{lineNumber}", "alignment": "left", "when": "editorLangId" },
          { "id": "demo.cmd", "text": "${'$'}{command:demo.x}", "alignment": "left" }
        ] }
    """), mapOf("lc.json" to "{}", "zig.tmLanguage.json" to "{}"))
    private val snapshot = ExtFixtures.snapshot(pack)
    private val langs = ExtensionLanguages(snapshot.languages, snapshot.grammars, snapshot.languageConfigurations)

    @Test fun `language detection by filename, longest extension, pattern and first line`() {
        assertEquals("zig", langs.languageFor("main.zig", null))
        assertEquals("zig", langs.languageFor("build.zig.zon", null))
        assertEquals("tpl", langs.languageFor("page.tpl.html", null))
        assertEquals("tpl", langs.languageFor("x.tmpl", null))
        assertEquals("tpl", langs.languageFor("run", "#!/usr/bin/env tplrun"))
        assertNull(langs.languageFor("a.py", null))
        assertEquals("source.zig", langs.scopeFor("a.zig", null))
        assertNull(langs.scopeFor("a.tmpl", null))
        assertEquals("/host/ext/zig.tmLanguage.json", langs.grammarFile("source.zig"))
        assertEquals("/host/ext/lc.json", langs.configurationFile("zig"))
    }

    @Test fun `status items render sync variables, sort and drop empty text`() {
        val editor = EditorState("/workspace/src/a.py", "python", 7, 1, "", "", "")
        val ws = WorkspaceState("/workspace", "demo", "env", "Env")
        val items = StatusItems.items(snapshot, ExtFixtures.context("editorLangId" to "\"python\""), emptySet(),
            { key -> if (key == "demo.interpreter") JsonPrimitive("python3") else null }, editor, ws)
        assertEquals(listOf("a.py:7", "Py python3"), items.map { it.text })
        val none = StatusItems.items(snapshot, ExtFixtures.context(), setOf("statusBar:demo.interp"), { null }, null, ws)
        assertEquals(emptyList<String>(), none.map { it.text })
    }

    @Test fun `tasks parse shell and process entries and match definitions`() {
        val tasks = Tasks.parse("""
            { "version": "2.0.0", "tasks": [
              { "label": "build", "type": "shell", "command": "make", "args": ["all files"], "options": { "cwd": "${'$'}{workspaceFolder}/src" } },
              { "label": "test", "command": "pytest", "args": ["-q"] },
              { "label": "npm", "type": "npm", "script": "x" },
              { "type": "shell", "command": "nolabel" } ] }
        """)!!
        assertEquals(listOf("build", "test"), tasks.map { it.label })
        assertEquals(listOf("/bin/sh", "-c", "make 'all files'"), tasks[0].argv { it })
        assertEquals(listOf("pytest", "-q"), tasks[1].argv { it })
        assertEquals("test", Tasks.matching(tasks, Json.parseToJsonElement("""{"label":"test"}"""))!!.label)
        assertNull(Tasks.matching(tasks, Json.parseToJsonElement("""{"type":"npm"}""")))
    }

    @Test fun `sync variables substitute workspace and editor values`() {
        val editor = EditorState("/workspace/src/a.py", "python", 3, 2, "sel", "w", "line")
        val ws = WorkspaceState("/workspace", "demo", "env", "Env")
        assertEquals("/workspace/src|src/a.py|a|.py|3", SyncVariables.render("\${fileDirname}|\${relativeFile}|\${fileBasenameNoExtension}|\${fileExtname}|\${lineNumber}", { null }, editor, ws, dev.easyide.extensions.contrib.Owner.BuiltIn))
        assertEquals("\${bad", SyncVariables.render("\${bad", { null }, editor, ws, dev.easyide.extensions.contrib.Owner.BuiltIn))
    }
}
