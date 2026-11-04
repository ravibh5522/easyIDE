package dev.easyide.sandbox.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupProgressTest {

    @Test fun `downloads fill the first quarter of the step`() {
        val update = SetupProgress.parse("dlstatus:3:50.0000:Retrieving file 3 of 6")!!
        assertEquals(0.125f, update.fraction, 0.0001f)
        assertNull(update.message)
    }

    @Test fun `unpacking and configuring fill the rest and carry the package message`() {
        val update = SetupProgress.parse("pmstatus:git:50.0000:Unpacking git (1:2.43.0-1ubuntu7.3) ...")!!
        assertEquals(0.625f, update.fraction, 0.0001f)
        assertEquals("Unpacking git (1:2.43.0-1ubuntu7.3) ...", update.message)
    }

    @Test fun `a message that itself contains colons is kept whole`() {
        assertEquals("Preparing to unpack a:b", SetupProgress.parse("pmstatus:x:1.0:Preparing to unpack a:b")!!.message)
    }

    @Test fun `the node installer's phase marker is read`() {
        assertEquals(0.4f, SetupProgress.parse("easyide-progress:40")!!.fraction, 0.0001f)
    }

    @Test fun `ordinary output is not progress`() {
        assertNull(SetupProgress.parse("Reading package lists..."))
        assertNull(SetupProgress.parse("Get:1 http://ports.ubuntu.com noble InRelease [256 kB]"))
        assertNull(SetupProgress.parse("pmstatus:git:abc:text"))
        assertNull(SetupProgress.parse("easyide-progress:abc"))
    }

    @Test fun `out of range percentages are clamped`() {
        assertEquals(1f, SetupProgress.parse("pmstatus:x:250:done")!!.fraction, 0.0001f)
    }
}
