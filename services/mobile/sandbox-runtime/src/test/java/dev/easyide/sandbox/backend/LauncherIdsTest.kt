package dev.easyide.sandbox.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LauncherIdsTest {

    private fun argv(ids: GuestIds?) = ProotLauncher(File("/data/bin/proot")).buildLaunchSpec(
        LaunchRequest(File("/data/env/rootfs"), null, "/workspace", listOf("/bin/bash", "-l"), ids = ids),
    ).argv

    @Test fun `without ids the guest runs as fake root`() {
        val args = argv(null)
        assertTrue("-0" in args)
        assertFalse("-i" in args)
    }

    @Test fun `with ids the guest runs as that user and group`() {
        val args = argv(GuestIds(1000, 0))
        assertEquals("1000:0", args[args.indexOf("-i") + 1])
        assertFalse("-0" in args)
    }
}
