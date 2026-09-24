package dev.easyide.ext.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class DevCommandTest {
    @get:Rule val tmp = TemporaryFolder()

    private val calls = ArrayList<List<String>>()
    private val fakeAdb = object : AdbRunner {
        override fun run(vararg args: String): String { calls += args.toList(); return "" }
    }

    @After fun tearDown() { DevCommand.adb = { SystemAdb() } }

    private fun cli(vararg args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val exit = run(args.toList(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"), tmp.root, File(tmp.root, "home"), emptyMap()) { null }
        return exit to out.toString("UTF-8") + err.toString("UTF-8")
    }

    @Test fun `adb mode pushes to the dev inbox and broadcasts to the protected receiver`() {
        DevCommand.adb = { fakeAdb }
        assertEquals(0, cli("init", "ext", "--template", "snippets", "--publisher", "acme").first)
        val (exit, out) = cli("dev", "ext", "--device", "R52T", "--app-id", "dev.easyide.app.canary")
        assertEquals(out, 0, exit)
        assertEquals(2, calls.size)
        val push = calls[0]
        assertEquals(listOf("-s", "R52T", "push"), push.take(3))
        assertEquals("/sdcard/Android/data/dev.easyide.app.canary/files/dev-inbox/acme.ext.easyext", push[4])
        assertEquals(
            listOf("-s", "R52T", "shell", "am", "broadcast", "-a", DevCommand.ACTION, "-n", "dev.easyide.app.canary/${DevCommand.RECEIVER}", "--es", "id", "acme.ext"),
            calls[1],
        )
    }

    @Test fun `an invalid extension is not deployed`() {
        DevCommand.adb = { fakeAdb }
        File(tmp.root, "bad").mkdirs(); File(tmp.root, "bad/package.json").writeText("{}")
        assertEquals(1, cli("dev", "bad").first)
        assertEquals(emptyList<Any>(), calls)
    }

    @Test fun `local mode writes a request naming the folder relative to the workspace`() {
        val ws = tmp.newFolder("workspace")
        assertEquals(0, cli("init", "workspace/tools/ext", "--template", "snippets", "--publisher", "acme").first)
        val (exit, out) = cli("dev", "workspace/tools/ext", "--local", "--workspace", ws.path)
        assertEquals(out, 0, exit)
        val req = Json.parseToJsonElement(File(ws, ".easyide/dev/acme.ext.json").readText()).jsonObject
        assertEquals("tools/ext", req["folder"]!!.jsonPrimitive.content)
        assertEquals("acme.ext", req["id"]!!.jsonPrimitive.content)
        // Outside the workspace the app could not map the folder back.
        assertEquals(0, cli("init", "elsewhere", "--template", "snippets", "--publisher", "acme").first)
        assertEquals(1, cli("dev", "elsewhere", "--local", "--workspace", ws.path).first)
        assertTrue(File(ws, ".easyide/dev").list()!!.size == 1)
    }
}
