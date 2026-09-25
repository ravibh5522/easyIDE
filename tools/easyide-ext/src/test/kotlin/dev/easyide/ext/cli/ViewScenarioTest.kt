package dev.easyide.ext.cli

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

/** `expect.views` scenarios and `validate` on a pack with views (extension-ui.md section 8, items 2 and 3). */
class ViewScenarioTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Result(val exit: Int, val stdout: String, val stderr: String) {
        val json get() = Json.parseToJsonElement(stdout.trim()).jsonObject
    }

    private fun cli(vararg args: String): Result {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val exit = run(args.toList(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"), tmp.root, File(tmp.root, "home"), emptyMap()) { null }
        return Result(exit, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    /** A copy of the schema module's docker fixture, the pack the scenarios drive. */
    private fun pack(): File {
        val dir = tmp.newFolder("docker")
        File("../../services/shared/extension-schema/src/testFixtures/fixtures/ui-docker").copyRecursively(dir, overwrite = true)
        return dir
    }

    private fun scenario(dir: File, name: String, body: String) = File(dir, "test/$name.json").apply { parentFile.mkdirs(); writeText(body) }

    private val containers = """[
        { "id": "a1", "name": "web", "image": "nginx", "state": "running", "status": "Up 2h" },
        { "id": "b2", "name": "db", "image": "postgres", "state": "exited", "status": "Exited" }]"""

    @Test fun `validate accepts a pack with views and reports a broken view against its own file`() {
        val dir = pack()
        assertEquals(cli("validate", dir.path).stderr, 0, cli("validate", dir.path).exit)
        File(dir, "views/containers.json").writeText("""{ "viewSchema": 1, "root": { "type": "hologram" } }""")
        val r = cli("validate", dir.path, "--json")
        assertEquals(1, r.exit)
        val d = r.json["diagnostics"]!!.jsonArray.map { it.jsonObject }.first { it["code"]!!.jsonPrimitive.content == "E_VIEW_COMPONENT" }
        assertEquals("views/containers.json", d["file"]!!.jsonPrimitive.content)
    }

    @Test fun `a scenario asserts the rendered tree of a view and of a document`() {
        val dir = pack()
        scenario(dir, "render", """{ "name": "render", "steps": [], "expect": { "views": [
            { "view": "acme.docker.containers", "data": { "containers": $containers },
              "types": ["list", "statusDot", "iconButton"], "texts": ["web", "nginx Up 2h", "Stop web", "Start db"], "absent": ["Start web", "Stop db"], "rows": 2 },
            { "view": "acme.docker/container", "data": { "name": "web", "state": "running", "logs": ["boot", "ready"] },
              "types": ["tabs", "logStream"], "texts": ["web", "running", "Logs", "Env", "ready"] }] } }""")
        val r = cli("test", dir.path, "--json")
        assertEquals(r.stdout + r.stderr, 0, r.exit)
        assertTrue(r.json["result"]!!.jsonObject["scenarios"]!!.jsonArray.single().jsonObject["passed"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun `mismatches name the missing type, text, row count and unknown view`() {
        val dir = pack()
        scenario(dir, "bad", """{ "name": "bad", "steps": [], "expect": { "views": [
            { "view": "acme.docker.containers", "data": { "containers": $containers }, "types": ["chart"], "texts": ["nope"], "absent": ["web"], "rows": 5 },
            { "view": "acme.docker.gone" }] } }""")
        val r = cli("test", dir.path, "--json")
        assertEquals(1, r.exit)
        val problems = r.json["result"]!!.jsonObject["scenarios"]!!.jsonArray.single().jsonObject["problems"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(problems.toString(), problems.any { it.contains("no 'chart' component") })
        assertTrue(problems.any { it.contains("text 'nope' not rendered") })
        assertTrue(problems.any { it.contains("text 'web' is rendered but expected absent") })
        assertTrue(problems.any { it.contains("expected 5 rows, got 2") })
        assertTrue(problems.any { it.contains("no view or document type 'acme.docker.gone'") })
    }
}
