package dev.easyide.ext.cli

import dev.easyide.extensions.registry.JdkEd25519
import dev.easyide.extensions.registry.RegistryVerifier
import dev.easyide.extensions.registry.Verified
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * cli.md sec 9 "Publish": against a local bare git repo, `publish` pushes a branch with a
 * publisher-signed entry, `registry build` root-signs the index, and the app-side
 * [RegistryVerifier] accepts the result end to end. Needs the system `git`.
 */
class RegistryFlowTest {
    @get:Rule val tmp = TemporaryFolder()

    private val identity = mapOf(
        "GIT_AUTHOR_NAME" to "Test", "GIT_AUTHOR_EMAIL" to "test@example.org",
        "GIT_COMMITTER_NAME" to "Test", "GIT_COMMITTER_EMAIL" to "test@example.org",
    )
    private val git = SystemGit(identity)

    @Before fun setUp() { PublishCommand.git = { git } }
    @After fun tearDown() { PublishCommand.git = { SystemGit() } }

    private class Result(val exit: Int, val stdout: String, val stderr: String) {
        val result get() = Json.parseToJsonElement(stdout.trim()).jsonObject["result"]!!.jsonObject
    }

    private fun cli(vararg args: String): Result {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val exit = run(args.toList(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"), tmp.root, File(tmp.root, "home"), emptyMap()) { null }
        return Result(exit, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    private fun keygen(publisher: String, dir: String): Pair<File, File> {
        val r = cli("keygen", "--publisher", publisher, "--out", dir, "--no-passphrase", "--json")
        assertEquals(r.stderr, 0, r.exit)
        return File(r.result["privateKey"]!!.jsonPrimitive.content) to File(r.result["publicKey"]!!.jsonPrimitive.content)
    }

    @Test fun `publish then registry build yields an index the app verifies`() {
        val (rootKey, rootPub) = keygen("registry", "root")
        val (pubKey, _) = keygen("acme", "keys")
        assertEquals(0, cli("init", "ember", "--template", "theme", "--publisher", "acme").exit)
        val pkg = cli("package", "ember", "--json").let { assertEquals(it.stderr, 0, it.exit); File(it.result["file"]!!.jsonPrimitive.content) }

        val bare = tmp.newFolder("origin.git")
        git.run(bare, "init", "--bare", "-b", "main")
        val seed = tmp.newFolder("seed")
        git.run(seed, "init", "-b", "main")
        File(seed, "README.md").writeText("index\n")
        git.run(seed, "add", "-A"); git.run(seed, "commit", "-m", "init"); git.run(seed, "push", bare.path, "main")

        val pub = cli("publish", pkg.path, "--index-repo", bare.path, "--key", pubKey.path, "--package-base", "https://example.org/index/raw/main", "--register-publisher", "--json")
        assertEquals(pub.stderr, 0, pub.exit)
        val branch = pub.result["branch"]!!.jsonPrimitive.content
        assertEquals("publish/acme.ember-0.1.0", branch)

        val maint = File(tmp.root, "maint")
        git.run(tmp.root, "clone", "-b", branch, bare.path, maint.path)
        assertTrue(File(maint, "entries/acme/ember/0.1.0.json").isFile)
        assertTrue(File(maint, "packages/acme/${pkg.name}").isFile)
        val build = cli("registry", "build", maint.path, "--root-key", rootKey.path, "--json")
        assertEquals(build.stderr, 0, build.exit)
        assertEquals(1, build.result["entries"]!!.jsonPrimitive.content.toInt())

        val verifier = RegistryVerifier(JdkEd25519)
        val (_, rootRaw) = Keys.loadPublic(rootPub)
        fun b(p: String) = File(maint, p).readBytes()
        val idx = (verifier.verifyIndex("main", rootRaw, b("index.json"), b("index.json.sig"), b("revocations.json"), b("revocations.json.sig"), null) as Verified.Ok).value
        val publisher = (verifier.verifyPublisher(rootRaw, b("publishers/acme.json"), b("publishers/acme.json.sig"), "acme") as Verified.Ok).value
        val entry = idx.entries.single()
        assertTrue(verifier.trustEntry(entry, publisher, idx.revocations, pinned = null) is Verified.Ok)
        assertEquals(Verified.Ok(Unit), verifier.checkBytes(entry, File(maint, "packages/acme/${pkg.name}").readBytes()))
        assertEquals("https://example.org/index/raw/main/packages/acme/${pkg.name}", entry.url)

        // Merged: publishing the same version again is refused.
        git.run(maint, "push", "origin", "HEAD:main")
        val again = cli("publish", pkg.path, "--index-repo", bare.path, "--key", pubKey.path, "--package-base", "https://example.org/x")
        assertEquals(1, again.exit)
        assertTrue(again.stderr, again.stderr.contains("already in the index"))
    }

    @Test fun `registry build refuses an entry not signed by its publisher`() {
        val (rootKey, _) = keygen("registry", "root")
        val (pubKey, _) = keygen("acme", "keys")
        val (otherKey, _) = keygen("acme", "other")
        assertEquals(0, cli("init", "ember", "--template", "theme", "--publisher", "acme").exit)
        val pkg = File(cli("package", "ember", "--json").result["file"]!!.jsonPrimitive.content)
        val bare = tmp.newFolder("origin.git").also { git.run(it, "init", "--bare", "-b", "main") }
        val seed = tmp.newFolder("seed")
        git.run(seed, "init", "-b", "main"); File(seed, "README.md").writeText("x"); git.run(seed, "add", "-A"); git.run(seed, "commit", "-m", "init"); git.run(seed, "push", bare.path, "main")
        // Registered with one key, signed with another the publisher file does not list.
        assertEquals(0, cli("publish", pkg.path, "--index-repo", bare.path, "--key", pubKey.path, "--url", "https://example.org/p.easyext", "--register-publisher").exit)
        val maint = File(tmp.root, "maint")
        git.run(tmp.root, "clone", "-b", "publish/acme.ember-0.1.0", bare.path, maint.path)
        val forged = cli("publish", pkg.path, "--index-repo", maint.path, "--key", otherKey.path, "--url", "https://example.org/p.easyext")
        assertEquals("unregistered key is refused at publish", 1, forged.exit)
        File(maint, "entries/acme/ember/0.1.0.json").writeText(File(maint, "entries/acme/ember/0.1.0.json").readText().replace("\"MIT\"", "\"GPL-3.0\""))
        val build = cli("registry", "build", maint.path, "--root-key", rootKey.path)
        assertEquals(1, build.exit)
        assertTrue(build.stderr, build.stderr.contains("does not verify"))
    }
}
