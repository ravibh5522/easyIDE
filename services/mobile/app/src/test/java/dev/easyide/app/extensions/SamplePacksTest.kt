package dev.easyide.app.extensions

import dev.easyide.app.extensions.install.AssetTree
import dev.easyide.app.extensions.install.SamplePacks
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.extensions.capability.Capability
import dev.easyide.extensions.contrib.ViewDataKind
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.Layer
import dev.easyide.extensions.manifest.ManifestParser
import dev.easyide.extensions.manifest.PackageLayout
import dev.easyide.extensions.manifest.PackageLayoutReader
import dev.easyide.extensions.manifest.PackageLimits
import dev.easyide.extensions.manifest.ParseOptions
import dev.easyide.extensions.manifest.ParseResult
import dev.easyide.extensions.schema.ManifestSchema
import dev.easyide.extensions.view.Into
import dev.easyide.extensions.view.IntoMode
import dev.easyide.extensions.view.Payload
import dev.easyide.extensions.view.PlanNode
import dev.easyide.extensions.view.PlanResult
import dev.easyide.extensions.view.ResultParse
import dev.easyide.extensions.view.ViewData
import dev.easyide.extensions.view.ViewDocument
import dev.easyide.extensions.view.ViewPlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The three optional sample packs (extension-ui.md section 8) as shipped in `assets/extension-samples`: they validate clean,
 * declare a minimal capability set, are not built in, and their scripts produce data that the app's own view planner draws.
 * The shell scripts run for real (`sh`, with a fake `docker` on the path, and a scratch project for the agent).
 */
class SamplePacksTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root = File("src/main/assets/extension-samples")
    private val parser = ManifestParser(ManifestSchema.validator, ParseOptions(builtInCommands = CommandIds.ALL))

    private fun load(id: String): ExtensionDescriptor {
        val layout = PackageLayoutReader.read(File(root, id), PackageLimits.DEFAULT) as PackageLayout.Ok
        val r = parser.parse(layout.files) as? ParseResult.Ok ?: error("$id does not validate: ${(parser.parse(layout.files) as ParseResult.Invalid).errors}")
        assertEquals("$id has warnings", emptyList<Any>(), r.warnings)
        return r.descriptor
    }

    private val docker by lazy { load("easyide.sample-docker") }
    private val agents by lazy { load("easyide.sample-agents") }
    private val chat by lazy { load("easyide.sample-chat") }

    private fun view(d: ExtensionDescriptor, id: String): ViewDocument = d.contributes.views.first { it.id == id }.schema!!
    private fun document(d: ExtensionDescriptor, type: String): ViewDocument = d.contributes.documents.first { it.type == type }.body

    // ---- shipping

    @Test fun `the samples are optional, listed for install and never among the built-in packs`() {
        val tree = object : AssetTree {
            override fun list(path: String): List<String> = File("src/main/assets/$path").list()?.toList().orEmpty()
            override fun open(path: String): InputStream = File("src/main/assets/$path").inputStream()
        }
        val samples = SamplePacks(tree).list()
        assertEquals(listOf("easyide.sample-agents", "easyide.sample-chat", "easyide.sample-docker"), samples.map { it.id })
        assertEquals(listOf("Agents (sample)", "Chat (sample)", "Docker (sample)"), samples.map { it.name })
        assertTrue(samples.all { it.description.isNotBlank() })
        assertTrue(BuiltInPackFixtures.ids().none { it.startsWith("easyide.sample-") })
        assertEquals("easyide.sample-docker", SamplePacks(tree).folder("easyide.sample-docker")?.name)
        assertEquals(null, SamplePacks(tree).folder("../extensions"))
    }

    @Test fun `each pack validates clean with the capabilities it needs and no more`() {
        assertEquals(setOf("sandbox.exec", "ui.contribute", "ui.stage"), docker.capabilities.items.map { it.id }.toSet())
        assertEquals(InstallScope.ENVIRONMENT, docker.scope)
        assertEquals(
            setOf("sandbox.exec", "ui.contribute", "ui.stage", "fs.project(read)", "network(api.anthropic.com)"),
            agents.capabilities.items.map { it.id }.toSet(),
        )
        assertEquals(InstallScope.ENVIRONMENT, agents.scope)
        assertEquals(setOf(Capability.UiContribute), chat.capabilities.items)
        assertEquals(InstallScope.GLOBAL, chat.scope)
        assertTrue(Layer.L2 in chat.layers)
    }

    @Test fun `the contribution points each pack promises are there`() {
        assertEquals(listOf("easyide.sample-docker.nav"), docker.contributes.navigation.map { it.id })
        assertEquals("runningCount", docker.contributes.navigation.single().badge?.path)
        assertEquals(listOf("easyide.sample-docker/container"), docker.contributes.documents.map { it.type })
        assertEquals(setOf("easyide.sample-docker.containers", "easyide.sample-docker.images"), docker.contributes.viewData.map { it.viewId }.toSet())
        assertTrue(docker.contributes.viewData.all { it.kind == ViewDataKind.OBJECT && (it.intervalSec ?: 0) >= 2 })
        assertEquals(2, agents.contributes.viewContainers.map { it.location.placement }.distinct().size)
        assertEquals(listOf("easyide.sample-agents/session"), agents.contributes.documents.map { it.type })
        assertEquals(listOf("easyide.sample-chat/room"), chat.contributes.documents.map { it.type })
        assertEquals(listOf("both"), chat.contributes.navigation.map { it.scope.wire })
    }

    // ---- running the scripts

    private class Run(val out: String, val code: Int)

    /** Runs `sh <pack>/bin/<script>` in [cwd] with [path] first on PATH; [env] adds variables. */
    private fun sh(pack: String, script: String, vararg args: String, cwd: File = tmp.root, path: String = System.getenv("PATH").orEmpty(), env: Map<String, String> = emptyMap()): Run {
        val builder = ProcessBuilder(listOf("sh", File(root, "$pack/bin/$script").absolutePath) + args).directory(cwd).redirectErrorStream(true)
        builder.environment().putAll(env)
        builder.environment()["PATH"] = path
        val p = builder.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("$script timed out", p.waitFor(30, TimeUnit.SECONDS))
        return Run(out, p.exitValue())
    }

    private fun exec(out: String, code: Int = 0) = Json.parseToJsonElement(
        """{ "exitCode": $code, "stdout": ${Json.encodeToString(kotlinx.serialization.serializer<String>(), out)}, "stderr": "" }""",
    )

    private fun merged(doc: ViewDocument, out: String): JsonObject =
        (ViewData.write(doc.state, Into(ViewData.MERGE, IntoMode.SET, ResultParse.JSON), exec(out)) as ViewData.Written.Data).data

    private fun plan(doc: ViewDocument, data: JsonObject): PlanNode = (ViewPlan.build(doc, data) as PlanResult.Ready).root

    private fun texts(n: PlanNode): List<String> {
        val rows = when (val p = n.payload) {
            is Payload.Rows -> (0 until p.rows.count).mapNotNull { p.rows.at(it) }
            is Payload.TableRows -> emptyList()
            else -> emptyList()
        }
        return n.text.values.toList() + (n.children + rows + listOfNotNull(n.empty)).flatMap(::texts)
    }

    private fun fakeDocker(): String {
        val bin = tmp.newFolder("fakebin")
        File(bin, "docker").apply {
            writeText(
                """#!/bin/sh
case "${'$'}1" in
  ps) printf 'a1b2c3|web|nginx:1.25|running|Up 2 hours\nd4e5f6|db "prod"|postgres:16|exited|Exited (0) 3 days ago\n' ;;
  images) printf 'nginx:1.25|sha256abc|187MB\n' ;;
  inspect) case "${'$'}3" in *Name*) printf '/web|nginx:1.25|running\n' ;; *) printf 'MODE=say "hi"\nPORT=80\n' ;; esac ;;
  logs) printf 'boot\nready "now"\n' ;;
esac
""",
            )
            setExecutable(true)
        }
        return bin.absolutePath + File.pathSeparator + System.getenv("PATH").orEmpty()
    }

    @Test fun `docker scripts print the JSON the views read, and the views draw it`() {
        val path = fakeDocker()
        val list = sh("easyide.sample-docker", "containers.sh", path = path)
        assertEquals(0, list.code)
        val doc = view(docker, "easyide.sample-docker.containers")
        val data = merged(doc, list.out)
        assertEquals(JsonPrimitive(1), data["runningCount"])
        val root = plan(doc, data)
        val rows = (root.children.first { it.payload is Payload.Rows }.payload as Payload.Rows).rows
        assertEquals(2, rows.count)
        val web = texts(rows.at(0)!!)
        assertTrue(web.toString(), "Stop web" in web && "Restart web" in web && "Start web" !in web)
        val db = texts(rows.at(1)!!)
        assertTrue(db.toString(), "Start db \"prod\"" in db && "Stop db \"prod\"" !in db)

        val images = merged(view(docker, "easyide.sample-docker.images"), sh("easyide.sample-docker", "images.sh", path = path).out)
        assertEquals(1, (images["images"] as kotlinx.serialization.json.JsonArray).size)

        val state = merged(document(docker, "easyide.sample-docker/container"), sh("easyide.sample-docker", "container.sh", "a1b2c3", path = path).out)
        assertEquals(JsonPrimitive("web"), state["name"])
        assertEquals(2, (state["logs"] as kotlinx.serialization.json.JsonArray).size)
        val docText = texts(plan(document(docker, "easyide.sample-docker/container"), state))
        assertTrue(docText.toString(), "web" in docText && "running" in docText && "Stop" in docText && "Start" !in docText)
    }

    @Test fun `without the docker cli the views say so instead of failing`() {
        val bin = tmp.newFolder("tools")
        for (tool in listOf("awk", "tr", "head", "sed", "cat", "dirname")) {
            val real = listOf("/usr/bin", "/bin").map { File(it, tool) }.firstOrNull { it.exists() }
            assumeTrue("$tool is needed for this test", real != null)
            Files.createSymbolicLink(File(bin, tool).toPath(), real!!.toPath())
        }
        val out = sh("easyide.sample-docker", "containers.sh", path = bin.absolutePath).out
        val doc = view(docker, "easyide.sample-docker.containers")
        val data = merged(doc, out)
        assertEquals(JsonPrimitive("docker is not installed in this environment"), data["error"])
        val shown = texts(plan(doc, data))
        assertTrue(shown.toString(), "docker is not installed in this environment" in shown)
    }

    @Test fun `the agent scripts keep a session in the project and the chat draws it`() {
        val project = tmp.newFolder("project")
        val env = mapOf("AGENT_COMMAND" to "echo \"reply: \$AGENT_PROMPT\"")
        val id = sh("easyide.sample-agents", "new.sh", "Fix the \"login\" bug", cwd = project).out.trim()
        assertTrue(id, Regex("s[0-9]+").matches(id))
        val reply = sh("easyide.sample-agents", "send.sh", id, "hello\nsecond \"line\"", cwd = project, env = env).out
        val message = Json.parseToJsonElement(reply) as JsonObject
        assertEquals(JsonPrimitive("assistant"), message["role"])
        assertEquals(JsonPrimitive("reply: hello\nsecond \"line\""), message["text"])

        val session = view(agents, "easyide.sample-agents.sessions.list")
        val listed = merged(session, sh("easyide.sample-agents", "sessions.sh", cwd = project).out)
        assertEquals(1, (listed["sessions"] as kotlinx.serialization.json.JsonArray).size)
        assertTrue(texts(plan(session, listed)).toString(), "Fix the \"login\" bug" in texts(plan(session, listed)))

        val doc = document(agents, "easyide.sample-agents/session")
        val data = merged(doc, sh("easyide.sample-agents", "session.sh", id, cwd = project).out)
        val chatNode = plan(doc, data).children.first { it.type.wire == "chat" }
        val messages = (chatNode.payload as Payload.Messages).messages
        assertEquals(listOf("user", "assistant"), messages.map { it.role })
        assertEquals("hello\nsecond \"line\"", messages[0].text)
    }

    @Test fun `the agent falls back to the echo agent, and an unusable session id is refused`() {
        val bin = tmp.newFolder("tools")
        for (tool in listOf("awk", "tr", "head", "sed", "cat", "date", "mkdir", "basename", "dirname", "tail", "grep", "ls")) {
            val real = listOf("/usr/bin", "/bin").map { File(it, tool) }.firstOrNull { it.exists() }
            assumeTrue("$tool is needed for this test", real != null)
            Files.createSymbolicLink(File(bin, tool).toPath(), real!!.toPath())
        }
        val project = tmp.newFolder("p2")
        val id = sh("easyide.sample-agents", "new.sh", "t", cwd = project, path = bin.absolutePath).out.trim()
        val out = sh("easyide.sample-agents", "send.sh", id, "ping", cwd = project, path = bin.absolutePath).out
        assertTrue(out, "echo agent: ping" in out)
        val bad = sh("easyide.sample-agents", "send.sh", "../x", "ping", cwd = project).out
        assertTrue(bad, "\"state\":\"error\"" in bad)
        assertFalse(File(project, ".easyide/agents/../x.jsonl").exists())
    }

    @Test fun `the changes script lists git status`() {
        val project = tmp.newFolder("repo")
        val git = ProcessBuilder("git", "init", "-q").directory(project).start()
        assumeTrue("git is needed for this test", runCatching { git.waitFor() == 0 }.getOrDefault(false))
        File(project, "a b.txt").writeText("x")
        val data = merged(view(agents, "easyide.sample-agents.changes"), sh("easyide.sample-agents", "changes.sh", cwd = project).out)
        val files = data["files"] as kotlinx.serialization.json.JsonArray
        assertEquals(JsonPrimitive("a b.txt"), (files.single() as JsonObject)["path"])
        val shown = texts(plan(view(agents, "easyide.sample-agents.changes"), data))
        assertTrue(shown.toString(), "a b.txt" in shown)
    }
}
