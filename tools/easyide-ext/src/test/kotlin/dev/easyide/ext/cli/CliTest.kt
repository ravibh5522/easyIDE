package dev.easyide.ext.cli

import dev.easyide.extensions.registry.JdkEd25519
import dev.easyide.extensions.registry.Sig
import dev.easyide.extensions.registry.SignatureVerifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class CliTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Result(val exit: Int, val stdout: String, val stderr: String) {
        val json: JsonObject get() = Json.parseToJsonElement(stdout.trim()).jsonObject
        fun codes(): List<String> = json["diagnostics"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }
    }

    private val env = mutableMapOf<String, String>()

    private fun cli(vararg args: String, cwd: File = tmp.root): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val home = File(tmp.root, "home").apply { mkdirs() }
        val exit = run(args.toList(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"), cwd, home, env) { null }
        return Result(exit, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    private fun write(dir: File, path: String, text: String) = File(dir, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun manifest(extra: String = "", contributes: String = "{}") = """
        { "name": "demo", "publisher": "acme", "version": "1.0.0", "license": "MIT",
          "engines": { "easyide": "^0.3.0" }, "contributes": $contributes $extra }
    """.trimIndent()

    // --- init -> validate --strict -> package -> validate the archive (cli.md sec 9 golden templates)

    @Test fun `every template validates strictly, packages and revalidates as an archive`() {
        for (t in InitCommand.TEMPLATES) {
            val dir = "ext-$t"
            assertEquals(t, 0, cli("init", dir, "--template", t, "--publisher", "acme").exit)
            val v = cli("validate", dir, "--strict", "--json")
            assertEquals("$t: ${v.stderr}", 0, v.exit)
            assertEquals(t, emptyList<String>(), v.codes())
            val p = cli("package", dir, "--json")
            assertEquals("$t: ${p.stderr}", 0, p.exit)
            val file = p.json["result"]!!.jsonObject["file"]!!.jsonPrimitive.content
            assertTrue(file.endsWith("acme.$dir-0.1.0.easyext"))
            assertEquals("$t archive", 0, cli("validate", file, "--strict").exit)
        }
    }

    @Test fun `init refuses a non-empty directory unless --yes, which only fills gaps`() {
        val dir = File(tmp.root, "x").apply { mkdirs() }
        write(dir, "README.md", "mine")
        assertEquals(1, cli("init", "x", "--template", "theme", "--publisher", "acme").exit)
        assertEquals(0, cli("init", "x", "--template", "theme", "--publisher", "acme", "--yes").exit)
        assertEquals("mine", File(dir, "README.md").readText())
        assertTrue(File(dir, "package.json").isFile)
        assertTrue(File(dir, ".gitignore").isFile)
    }

    // --- package

    @Test fun `package is byte-identical across runs and ignores author-side files`() {
        val dir = tmp.newFolder("p")
        write(dir, "package.json", manifest())
        write(dir, "README.md", "r")
        write(dir, "test/case.json", "{}")
        write(dir, ".git/HEAD", "ref")
        write(dir, ".easyide-ext.json", """{"publisher":"acme"}""")
        write(dir, "notes/private.txt", "secret")
        write(dir, ".easyextignore", "notes/\n")
        val first = cli("package", "p", "--out", "a.easyext")
        assertEquals(first.stderr, 0, first.exit)
        File(dir, "README.md").setLastModified(0)
        assertEquals(0, cli("package", "p", "--out", "b.easyext").exit)
        val a = File(tmp.root, "a.easyext").readBytes()
        assertArrayEquals(a, File(tmp.root, "b.easyext").readBytes())
        val names = ZipFile(File(tmp.root, "a.easyext")).use { z -> z.entries().toList().map { it.name } }
        assertEquals(listOf("README.md", "package.json"), names)
    }

    @Test fun `package refuses an invalid extension with exit 1`() {
        val dir = tmp.newFolder("bad")
        write(dir, "package.json", """{ "name": "demo" }""")
        val r = cli("package", "bad", "--json")
        assertEquals(1, r.exit)
        assertTrue(r.codes().toString(), "E_SCHEMA" in r.codes())
        assertFalse(File(dir, "dist").exists())
    }

    // --- validate: same codes as the app, plus CLI checks

    @Test fun `validator reports stable codes`() {
        fun codes(files: Map<String, String>, vararg flags: String): Pair<Int, List<String>> {
            val dir = tmp.newFolder()
            files.forEach { (p, t) -> write(dir, p, t) }
            val r = cli("validate", dir.path, "--json", *flags)
            return r.exit to r.codes()
        }
        assertEquals(1 to listOf("E_MANIFEST_MISSING"), codes(mapOf("README.md" to "x")))
        val undeclared = codes(
            mapOf(
                "package.json" to manifest(
                    contributes = """{ "commands": [{ "command": "demo.run", "title": "Run" }] }""",
                    extra = """, "easyide": { "actions": { "demo.run": { "type": "runInTerminal", "command": "make" } } }""",
                ),
            ),
        )
        assertEquals(1, undeclared.first)
        assertTrue(undeclared.second.toString(), "E_CAP_UNDECLARED" in undeclared.second)
        val badWhen = codes(mapOf("package.json" to manifest(contributes = """{ "keybindings": [{ "command": "workbench.action.files.save", "key": "ctrl+s", "when": "a &&" }] }""")))
        assertTrue(badWhen.second.toString(), "E_WHEN_SYNTAX" in badWhen.second)
        // Built-in command ids resolve, as in the app.
        assertEquals(0 to emptyList<String>(), codes(mapOf("package.json" to manifest(contributes = """{ "menus": { "editor/touchToolbar": [{ "command": "workbench.action.files.save" }] } }"""), "README.md" to "r", "LICENSE" to "l"), "--strict"))
        // --strict turns registry readiness warnings into failure.
        val loose = codes(mapOf("package.json" to manifest()))
        assertEquals(0, loose.first)
        val strict = codes(mapOf("package.json" to manifest()), "--strict")
        assertEquals(1, strict.first)
        assertTrue(strict.second.containsAll(listOf("W_README_MISSING", "W_LICENSE_MISSING")))
        // --engine outside engines.easyide is an error.
        val engine = codes(mapOf("package.json" to manifest()), "--engine", "1.0.0")
        assertEquals(1 to listOf("E_ENGINE_MISMATCH"), engine)
    }

    @Test fun `archives are audited like an install`() {
        val zip = File(tmp.root, "evil.easyext")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("package.json")); z.write(manifest().toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("../escape.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        val r = cli("validate", zip.path, "--json")
        assertEquals(1, r.exit)
        assertEquals(listOf("E_PACKAGE_PATH"), r.codes())
        assertFalse(File(tmp.root.parentFile, "escape.txt").exists())
    }

    @Test fun `the app's built-in packs validate with no errors and no unknown commands`() {
        val packs = File("../../services/mobile/app/src/main/assets/extensions").listFiles()!!.filter { it.isDirectory }
        assertTrue(packs.size >= 5)
        for (p in packs) {
            val r = cli("validate", p.absolutePath, "--json")
            assertEquals("${p.name}: ${r.stderr}", 0, r.exit)
            assertFalse(p.name, "W_COMMAND_UNKNOWN" in r.codes())
        }
    }

    // --- keys and signatures

    @Test fun `keygen, sign and verify round trip, and tampering is caught`() {
        env[Keys.PASSPHRASE_ENV] = "correct horse"
        val kg = cli("keygen", "--publisher", "acme", "--out", "keys", "--json")
        assertEquals(kg.stderr, 0, kg.exit)
        val keyId = kg.json["result"]!!.jsonObject["keyId"]!!.jsonPrimitive.content
        val key = File(tmp.root, "keys/acme-$keyId.key")
        val pub = File(tmp.root, "keys/acme-$keyId.pub.json")
        assertTrue(key.readText().startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----"))
        assertFalse("key material must never reach --json output", kg.stdout.contains("PRIVATE KEY") || kg.stdout.contains(key.readText().lines()[1]))

        val pkg = File(tmp.root, "a.easyext").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(0, cli("sign", pkg.path, "--key", key.path).exit)
        assertEquals(0, cli("verify", pkg.path, "--pub", pub.path).exit)
        // The shared verifier the app uses accepts the same signature.
        val sig = Sig.fromJson(Json.parseToJsonElement(File(pkg.path + ".sig").readText()))!!
        val (_, raw) = Keys.loadPublic(pub)
        assertTrue(SignatureVerifier(JdkEd25519).verifyPackage(pkg.readBytes(), sig, raw))

        pkg.appendBytes(byteArrayOf(4))
        assertEquals(1, cli("verify", pkg.path, "--pub", pub.path).exit)

        env[Keys.PASSPHRASE_ENV] = "wrong"
        val wrong = cli("sign", pkg.path, "--key", key.path, "--json")
        assertEquals(1, wrong.exit)
        assertEquals(listOf("E_KEY"), wrong.codes())
    }

    @Test fun `sign refuses a key other than the one recorded for the extension`() {
        cli("keygen", "--publisher", "acme", "--out", "keys", "--no-passphrase")
        val key = File(tmp.root, "keys").listFiles()!!.single { it.name.endsWith(".key") }
        write(tmp.root, ".easyide-ext.json", """{ "keyId": "0000000000000000" }""")
        val pkg = File(tmp.root, "a.easyext").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(1, cli("sign", pkg.path, "--key", key.path).exit)
        assertEquals(0, cli("sign", pkg.path, "--key", key.path, "--yes").exit)
    }

    @Test fun `rotation record is signed by the old key over the canonical triple`() {
        cli("keygen", "--publisher", "acme", "--out", "old", "--no-passphrase")
        val old = File(tmp.root, "old").listFiles()!!.single { it.name.endsWith(".key") }
        val r = cli("keygen", "--publisher", "acme", "--out", "new", "--no-passphrase", "--rotate", "--from", old.path, "--json")
        assertEquals(r.stderr, 0, r.exit)
        val record = Json.parseToJsonElement(File(r.json["result"]!!.jsonObject["rotation"]!!.jsonPrimitive.content).readText()).jsonObject
        val (oldId, oldRaw) = Keys.loadPublic(File(old.path.removeSuffix(".key") + ".pub.json"))
        assertEquals(oldId, record["from"]!!.jsonPrimitive.content)
        val bytes = dev.easyide.extensions.registry.SignedBytes.rotation("acme", oldId, record["to"]!!.jsonPrimitive.content)
        val sig = java.util.Base64.getDecoder().decode(record["sigByOld"]!!.jsonPrimitive.content)
        assertTrue(JdkEd25519.verify(oldRaw, bytes, sig))
    }

    // --- exit codes (cli.md sec 3)

    @Test fun `usage errors exit 1 and I-O errors exit 2`() {
        assertEquals(1, cli("frobnicate").exit)
        assertEquals(1, cli("validate", "--bogus").exit)
        assertEquals(1, cli("publish", "x.easyext").exit)
        assertEquals(2, cli("validate", "does-not-exist").exit)
        assertEquals(2, cli("sign", "missing.easyext", "--key", "missing.key").exit)
        assertEquals(0, cli("help").exit)
    }

    @Test fun `json mode prints exactly one object on stdout`() {
        val r = cli("validate", "nowhere", "--json")
        assertEquals(1, r.stdout.trim().lines().size)
        assertEquals(false, r.json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("E_IO"), r.codes())
    }
}
