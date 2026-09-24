package dev.easyide.app.extensions.registry

import dev.easyide.app.extensions.install.RollbackResult
import dev.easyide.app.ui.screens.extensions.BrowseState
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.Source
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The registry install pipeline end to end over a generated, signed index and an in-memory
 * server (registry-and-install.md sec 17 "Pipeline", "Offline", "M6 exit"): tampered
 * package and revoked key both hard-fail; nothing here touches the real network.
 */
class RegistryInstallTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun world(): RegistryWorld = RegistryWorld(tmp.newFolder("files")).apply {
        addEntry("zig", "1.0.0")
        publishIndex()
        publishPublisher()
    }

    private fun ready(r: RegistryPrepare): RegistryStaged = (r as? RegistryPrepare.Ready)?.staged ?: throw AssertionError("not ready: $r")

    private fun rejected(r: RegistryPrepare): String {
        val f = r as? RegistryPrepare.Failed ?: throw AssertionError("expected a failure, got $r")
        assertTrue("hard failure expected: ${f.error}", f.error is RegistryError.Rejected)
        return f.error.reason
    }

    @Test fun `full flow - refresh, browse, install signed, record source, signer and pin`() = runTest {
        val w = world()
        val d = w.device()
        d.configure()
        assertTrue(d.service.view.value.configured)
        assertNull(d.service.view.value.statuses.single().cached) // nothing fetched yet
        d.service.refreshAll()
        val status = d.service.view.value.statuses.single()
        assertNull(status.error)
        assertEquals(0L, status.age(w.now)!!.toMinutes())

        val browse = BrowseState.build(d.service.view.value, d.service.records.value, "zig", d.inventory.installed.value, w.now)
        val item = browse.items.single()
        assertEquals("test", item.registryId)
        assertEquals("1.0.0", item.entry!!.version.toString())
        assertNull(item.installedVersion)

        val staged = ready(d.service.prepare("test", item.entry!!))
        assertEquals(w.pub1.id, staged.signedBy)
        assertFalse(staged.fromCache)
        assertNull("browsing and staging never pin", d.service.trust.pinned("test", "acme"))
        d.service.commit(staged, null)

        val installed = d.inventory.installed.value.single()
        assertEquals(Source.REGISTRY, installed.source)
        assertEquals("1.0.0", installed.directory.name)
        val entry = d.state.read().installs.single()
        assertEquals("test", entry.origin!!.registryId)
        assertEquals(w.pub1.id, entry.origin!!.signedBy)
        assertEquals(item.entry!!.sha256, entry.origin!!.sha256)
        assertEquals(w.pub1.id, d.service.trust.pinned("test", "acme"))
        assertTrue(d.service.cache.file(item.entry!!.sha256).isFile)
        assertEquals("1.0.0", BrowseState.build(d.service.view.value, d.service.records.value, "", d.inventory.installed.value, w.now).items.single().installedVersion)
    }

    @Test fun `a cache-hit install works while the network port throws, and within 10 s`() = runTest {
        val w = world()
        val first = w.device()
        first.configure()
        first.service.refreshAll()
        first.install("zig", "1.0.0")
        first.installer.uninstall(first.inventory.installed.value.single())
        assertTrue(first.inventory.installed.value.isEmpty())

        // A new process, fully offline: any use of the network port fails the test.
        w.server.forbidden = true
        val d = w.device()
        d.configure()
        val status = d.service.view.value.statuses.single()
        assertNotNull("the last verified index is loaded from disk", status.cached)
        val started = System.nanoTime()
        val staged = ready(d.service.prepare("test", d.entry("zig", "1.0.0")))
        assertTrue(staged.fromCache)
        d.service.commit(staged, null)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("offline signed install took $elapsedMs ms", elapsedMs < 10_000)
        assertEquals("1.0.0", d.inventory.installed.value.single().directory.name)
    }

    @Test fun `tampered package bytes fail hard and leave nothing behind`() = runTest {
        val w = world()
        val d = w.device()
        d.configure()
        d.service.refreshAll()
        val url = w.packageUrl("zig", "1.0.0")
        val good = w.server.files.getValue(url)
        w.server.files[url] = good.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        val entry = d.entry("zig", "1.0.0")
        assertTrue(rejected(d.service.prepare("test", entry)).contains("sha256"))
        w.server.files[url] = good + byteArrayOf(0)
        rejected(d.service.prepare("test", entry))
        assertTrue(d.inventory.installed.value.isEmpty())
        assertEquals(emptyList<String>(), d.paths.extensionPackageCacheDir.list().orEmpty().toList())
        assertNull(d.service.trust.pinned("test", "acme"))

        // A tampered cache file is never used: it is dropped and the package fetched again.
        w.server.files[url] = good
        d.service.cache.file(entry.sha256).apply { parentFile.mkdirs(); writeBytes(good.copyOf().also { it[0] = 9 }) }
        val staged = ready(d.service.prepare("test", entry))
        assertFalse(staged.fromCache)
    }

    @Test fun `a tampered index fails hard and keeps the last verified copy`() = runTest {
        val w = world()
        val d = w.device()
        d.configure()
        d.service.refreshAll()
        val before = d.service.view.value.statuses.single().cached!!
        val url = RegistryWorld.BASE + "index.json"
        w.server.files[url] = String(w.server.files.getValue(url)).replace("zig support", "evil support").toByteArray()
        w.now = w.now.plusSeconds(3600)
        d.service.refreshAll()
        val status = d.service.view.value.statuses.single()
        assertTrue("${status.error}", status.error is RegistryError.Rejected)
        assertTrue(status.error!!.reason.contains("does not verify"))
        assertEquals(before.fetchedAt, status.cached!!.fetchedAt)
        assertEquals("zig support", d.entry("zig", "1.0.0").description)
        // Signed by a key that is not the root key: refused the same way.
        w.publishIndex(signer = w.pub1)
        d.service.refreshAll()
        assertTrue(d.service.view.value.statuses.single().error is RegistryError.Rejected)
    }

    @Test fun `anti-rollback - an older validly signed index is refused, also after a restart`() = runTest {
        val w = world()
        val d = w.device()
        d.configure()
        w.publishIndex(generatedAt = "2026-09-24T09:00:00Z", revocationsAt = "2026-09-24T09:00:00Z")
        d.service.refreshAll()
        w.publishIndex(generatedAt = "2026-09-23T09:00:00Z", revocationsAt = "2026-09-24T09:00:00Z")
        d.service.refreshAll()
        assertTrue(d.service.view.value.statuses.single().error!!.reason.contains("older"))
        w.publishIndex(generatedAt = "2026-09-24T09:00:00Z", revocationsAt = "2026-09-20T00:00:00Z")
        val restarted = w.device()
        restarted.configure()
        restarted.service.refreshAll()
        val error = restarted.service.view.value.statuses.single().error
        assertTrue("$error", error is RegistryError.Rejected && error.reason.contains("revocations.json is older"))
    }

    @Test fun `offline refresh keeps the last verified index and its age, and 304s reuse it`() = runTest {
        val w = world()
        val d = w.device()
        d.configure()
        d.service.refreshAll()
        w.now = w.now.plusSeconds(90 * 60)
        w.server.requests.clear()
        d.service.refreshAll()
        assertNull(d.service.view.value.statuses.single().error)
        assertEquals(4, w.server.requests.size) // conditional GETs, all 304
        assertEquals(0L, d.service.view.value.statuses.single().age(w.now)!!.toMinutes())

        w.now = w.now.plusSeconds(20L * 24 * 3600)
        w.server.offline = true
        d.service.refreshAll()
        val status = d.service.view.value.statuses.single()
        assertTrue(status.error is RegistryError.Network)
        assertEquals(20L, status.age(w.now)!!.toDays())
        assertTrue(status.stale(w.now))
        assertNotNull(d.entry("zig", "1.0.0"))
    }

    @Test fun `a revoked key fails hard and disables the installed version without uninstalling it`() = runTest {
        val w = world()
        val d = w.device()
        d.configure()
        d.service.refreshAll()
        d.install("zig", "1.0.0")
        assertFalse(d.inventory.installed.value.single().revoked)

        w.publishIndex(generatedAt = "2026-09-24T09:00:00Z", revocationsAt = "2026-09-24T09:00:00Z", revokedKeys = listOf(w.pub1.id))
        d.service.refreshAll()
        val pkg = d.inventory.installed.value.single()
        assertTrue("disabled with reason REVOKED", pkg.revoked)
        assertTrue(pkg.directory.isDirectory)
        assertTrue(d.service.records.value.revoked.getValue("acme.zig@1.0.0").contains(w.pub1.id))
        assertEquals(1, w.notices.size)

        // Never installable again, from the registry or the cache.
        assertTrue(rejected(d.service.prepare("test", d.entry("zig", "1.0.0"))).contains("revoked"))
        assertTrue(d.service.isRevoked("acme.zig", "1.0.0"))
    }

    @Test fun `a revoked version range disables the install and rollback to a revoked version is refused`() = runTest {
        val w = world()
        w.addEntry("zig", "1.1.0")
        w.publishIndex()
        val d = w.device()
        d.configure()
        d.service.refreshAll()
        d.install("zig", "1.0.0")
        d.install("zig", "1.1.0")
        assertEquals("1.1.0", d.inventory.installed.value.single().directory.name)

        w.publishIndex(generatedAt = "2026-09-24T09:00:00Z", revocationsAt = "2026-09-24T09:00:00Z", revokedVersions = listOf("acme.zig" to "<=1.0.0"))
        d.service.refreshAll()
        assertFalse("1.1.0 is not in the range", d.inventory.installed.value.single().revoked)
        val r = d.installer.rollback("acme.zig", InstallScope.GLOBAL, null)
        assertTrue("$r", r is RollbackResult.Refused && r.problems.single().contains("revoked"))
        assertEquals("1.1.0", d.inventory.installed.value.single().directory.name)
    }

    @Test fun `a key rotation is accepted and re-pins, a key change without one is refused`() = runTest {
        val w = world()
        val d = w.device()
        d.configure()
        d.service.refreshAll()
        d.install("zig", "1.0.0")
        assertEquals(w.pub1.id, d.service.trust.pinned("test", "acme"))

        // 1.1.0 signed by a new key the publisher file lists, but with no rotation record.
        w.addEntry("zig", "1.1.0", key = w.pub2)
        w.publishIndex(generatedAt = "2026-09-24T09:00:00Z")
        w.publishPublisher(keys = listOf(w.pub1, w.pub2))
        d.service.refreshAll()
        assertTrue(rejected(d.service.prepare("test", d.entry("zig", "1.1.0"))).contains("key changed"))

        // With pub1 -> pub2 signed by pub1: accepted, and the pin moves when the install commits.
        w.publishPublisher(keys = listOf(w.pub1, w.pub2), rotations = listOf(w.pub1 to w.pub2), retired = setOf(w.pub1))
        val staged = ready(d.service.prepare("test", d.entry("zig", "1.1.0")))
        assertEquals(w.pub1.id, d.service.trust.pinned("test", "acme"))
        d.service.commit(staged, null)
        assertEquals(w.pub2.id, d.service.trust.pinned("test", "acme"))
        assertEquals("1.1.0", d.inventory.installed.value.single().directory.name)

        // The retained 1.0.0 was signed by pub1: revoking that key makes rollback to it refused.
        w.publishIndex(generatedAt = "2026-09-24T10:00:00Z", revocationsAt = "2026-09-24T10:00:00Z", revokedKeys = listOf(w.pub1.id))
        d.service.refreshAll()
        assertFalse(d.inventory.installed.value.single().revoked)
        assertTrue(d.installer.rollback("acme.zig", InstallScope.GLOBAL, null) is RollbackResult.Refused)

        d.service.forgetPin("test", "acme")
        assertNull(d.service.trust.pinned("test", "acme"))
    }

    @Test fun `an update is only a badge, and no registry configured is an explicit state`() = runTest {
        val w = world()
        val d = w.device()
        assertFalse(BrowseState.build(d.service.view.value, d.service.records.value, "", emptyList(), w.now).configured)
        d.configure()
        d.service.refreshAll()
        d.install("zig", "1.0.0")
        w.addEntry("zig", "1.2.0")
        w.publishIndex(generatedAt = "2026-09-24T09:00:00Z")
        d.service.refreshAll()
        val browse = BrowseState.build(d.service.view.value, d.service.records.value, "", d.inventory.installed.value, w.now)
        assertEquals(mapOf("acme.zig" to "1.2.0"), browse.updates)
        assertTrue(browse.items.single().updateAvailable)
        assertEquals("1.0.0", d.inventory.installed.value.single().directory.name) // nothing installed by itself
        assertFalse(File(d.paths.globalExtensionsDir, "acme.zig/1.2.0").exists())
    }
}
