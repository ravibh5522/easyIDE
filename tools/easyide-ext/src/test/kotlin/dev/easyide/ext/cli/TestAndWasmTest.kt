package dev.easyide.ext.cli

import com.dylibso.chicory.wabt.Wat2Wasm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class TestAndWasmTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Result(val exit: Int, val stdout: String, val stderr: String) {
        val json get() = Json.parseToJsonElement(stdout.trim()).jsonObject
        fun codes() = json["diagnostics"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }
    }

    private fun cli(vararg args: String): Result {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val exit = run(args.toList(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"), tmp.root, File(tmp.root, "home"), emptyMap()) { null }
        return Result(exit, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    private fun write(dir: File, path: String, text: String) = File(dir, path).apply { parentFile.mkdirs(); writeText(text) }

    @Test fun `every template's own scenarios pass out of the box`() {
        for (t in InitCommand.TEMPLATES) {
            assertEquals(0, cli("init", "x-$t", "--template", t, "--publisher", "acme").exit)
            if (t.startsWith("wasm-") && !WasmBuild.build(File(tmp.root, "x-$t"))) continue
            val r = cli("test", "x-$t", "--json")
            assertEquals("$t: ${r.stderr}", 0, r.exit)
            val scenarios = r.json["result"]!!.jsonObject["scenarios"]!!.jsonArray
            assertTrue(t, scenarios.isNotEmpty())
        }
    }

    @Test fun `a scenario drives actions through fakes and reports mismatches`() {
        val dir = tmp.newFolder("py")
        write(dir, "package.json", """
            { "name": "py", "publisher": "acme", "version": "1.0.0", "engines": { "easyide": "^0.3.0" },
              "contributes": {
                "commands": [ { "command": "py.run", "title": "Run" }, { "command": "py.pick", "title": "Pick" } ],
                "menus": { "editor/title": [ { "command": "py.run", "when": "editorLangId == python" } ] },
                "configuration": { "properties": { "py.interpreter": { "type": "string", "default": "python3" } } } },
              "easyide": { "capabilities": ["sandbox.exec"], "actions": {
                "py.run": { "type": "runInTerminal", "terminal": "Py", "command": "${'$'}{config:py.interpreter} ${'$'}{file}" },
                "py.pick": { "type": "sequence", "steps": [
                  { "type": "sandboxExec", "command": ["sh", "-c", "ls"], "output": "capture", "as": "found" },
                  { "type": "showQuickPick", "id": "i", "itemsFrom": "${'$'}{result:found}" },
                  { "type": "setConfig", "key": "py.interpreter", "value": "${'$'}{input:i}", "target": "environment" },
                  { "type": "runInTerminal", "terminal": "Py", "command": "${'$'}{config:py.interpreter} -V" } ] } } } }
        """.trimIndent())
        write(dir, "test/pick.json", """
            { "name": "picking an interpreter makes Run use it",
              "editor": { "path": "/workspace/a.py", "text": "" },
              "fakes": { "sandboxExec": [ { "match": ["sh", "-c", "*"], "stdout": "/usr/bin/python3\n/workspace/.venv/bin/python\n" } ],
                         "quickPick": ["/workspace/.venv/bin/python"] },
              "steps": [ { "execute": "py.pick" }, { "execute": "py.run" } ],
              "expect": { "visible": ["menu:editor/title:py.run"],
                          "calls": [ { "type": "setConfig", "key": "py.interpreter", "value": "/workspace/.venv/bin/python" },
                                     { "type": "runInTerminal", "command": "cd '/workspace' && '/workspace/.venv/bin/python' '/workspace/a.py'" } ] } }
        """.trimIndent())
        write(dir, "test/wrong.json", """
            { "name": "a wrong expectation fails", "editor": { "path": "/workspace/a.py", "text": "" },
              "steps": [ { "execute": "py.run" } ],
              "expect": { "calls": [ { "type": "runInTerminal", "command": "ruby a.rb" } ] } }
        """.trimIndent())
        val all = cli("test", "py", "--json")
        assertEquals(all.stderr, 1, all.exit)
        val results = all.json["result"]!!.jsonObject["scenarios"]!!.jsonArray.associate {
            it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["passed"]!!.jsonPrimitive.content.toBoolean()
        }
        assertEquals(mapOf("picking an interpreter makes Run use it" to true, "a wrong expectation fails" to false), results)
        assertEquals(0, cli("test", "py", "--filter", "pick").exit)
    }

    private fun wasmPack(wat: String): File {
        val dir = tmp.newFolder()
        write(dir, "package.json", """
            { "name": "w", "publisher": "acme", "version": "1.0.0", "engines": { "easyide": "^0.3.0" },
              "easyide": { "wasm": { "module": "./wasm/main.wasm", "abi": 1 } } }
        """.trimIndent())
        File(dir, "wasm").mkdirs()
        File(dir, "wasm/main.wasm").writeBytes(Wat2Wasm.parse(wat))
        return dir
    }

    private val exports = """
          (memory (export "memory") 1 16)
          (func (export "alloc") (param i32) (result i32) (i32.const 1024))
          (func (export "free") (param i32 i32))
          (func (export "ext_activate") (param i32 i32) (result i32) (i32.const 0))
          (func (export "ext_handle") (param i32 i32) (result i32) (i32.const 0))"""

    @Test fun `validate runs the app's static WASM check`() {
        val good = wasmPack("""(module (import "easyide" "host_call" (func (param i32 i32) (result i32))) $exports (func (export "ext_abi_version") (result i32) (i32.const 1)))""")
        assertEquals(cli("validate", good.path).stderr, 0, cli("validate", good.path).exit)
        val wasi = wasmPack("""(module (import "wasi_snapshot_preview1" "fd_write" (func (param i32 i32 i32 i32) (result i32))) $exports (func (export "ext_abi_version") (result i32) (i32.const 1)))""")
        val r = cli("validate", wasi.path, "--json")
        assertEquals(1, r.exit)
        assertEquals(listOf("E_WASM_MODULE"), r.codes())
        val noAbi = wasmPack("(module $exports)")
        assertEquals(1, cli("validate", noAbi.path).exit)
    }
}
