package dev.easyide.sandbox.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GuestNodeTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `the installer is written executable and kept current`() {
        val rootfs = File(tmp.root, "rootfs").apply { mkdirs() }
        GuestNode.ensure(rootfs)
        val script = File(rootfs, "usr/local/bin/${GuestNode.COMMAND}")
        assertEquals(GuestNode.SCRIPT, script.readText())
        assertTrue(script.canExecute())
        script.writeText("stale")
        GuestNode.ensure(rootfs)
        assertEquals(GuestNode.SCRIPT, script.readText())
    }

    @Test fun `the script checks the download and skips a new enough node`() {
        assertTrue(GuestNode.SCRIPT.startsWith("#!/bin/sh\n"))
        assertTrue("sha256sum -c" in GuestNode.SCRIPT)
        assertTrue("-ge 20" in GuestNode.SCRIPT)
        assertTrue("latest-v22.x" in GuestNode.SCRIPT)
    }
}
