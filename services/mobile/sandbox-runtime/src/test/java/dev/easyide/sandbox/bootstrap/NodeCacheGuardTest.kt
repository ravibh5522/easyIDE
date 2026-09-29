package dev.easyide.sandbox.bootstrap

import dev.easyide.sandbox.backend.LaunchRequest
import dev.easyide.sandbox.backend.ProotLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NodeCacheGuardTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun rootfs(): File = File(tmp.root, "rootfs").apply { mkdirs() }

    private fun cacheBlob(rootfs: File) = File(rootfs, "root/.npm/_cacache/content-v2/x").apply {
        parentFile.mkdirs()
        writeText("blob")
    }

    @Test fun `the preload is written and kept current`() {
        val rootfs = rootfs()
        NodeCacheGuard.ensure(rootfs)
        val preload = File(rootfs, NodeCacheGuard.PRELOAD_GUEST_PATH.removePrefix("/"))
        assertEquals(NodeCacheGuard.PRELOAD, preload.readText())
        preload.writeText("stale")
        NodeCacheGuard.ensure(rootfs)
        assertEquals(NodeCacheGuard.PRELOAD, preload.readText())
    }

    @Test fun `a broken legacy cache is removed once, later caches are kept`() {
        val rootfs = rootfs()
        val old = cacheBlob(rootfs)
        NodeCacheGuard.ensure(rootfs)
        assertFalse(old.exists())
        val fresh = cacheBlob(rootfs)
        NodeCacheGuard.ensure(rootfs)
        assertTrue(fresh.exists())
    }

    private fun env(rootfs: File) = ProotLauncher(File("/bin/proot")).buildLaunchSpec(
        LaunchRequest(rootfs, null, "/workspace", listOf("node")),
    ).environment["NODE_OPTIONS"]

    @Test fun `node is only told to preload a file that exists`() {
        val rootfs = rootfs()
        assertNull(env(rootfs))
        NodeCacheGuard.ensure(rootfs)
        assertEquals("--require=${NodeCacheGuard.PRELOAD_GUEST_PATH}", env(rootfs))
    }
}
