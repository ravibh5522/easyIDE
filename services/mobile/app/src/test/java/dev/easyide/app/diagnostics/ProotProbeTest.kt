package dev.easyide.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotProbeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `the version is the first non-blank line, trimmed`() {
        assertEquals("proot 5.4.0", ProotProbe.parseVersion("\n  proot 5.4.0  \nCopyright (C) ...\n"))
        assertEquals("v1", ProotProbe.parseVersion("v1"))
    }

    @Test fun `no output means no version`() {
        assertNull(ProotProbe.parseVersion(""))
        assertNull(ProotProbe.parseVersion("  \n\t\n"))
    }

    @Test fun `a very long first line is bounded`() {
        assertEquals(120, ProotProbe.parseVersion("v".repeat(1000))!!.length)
    }

    @Test fun `a missing binary is reported as missing`() {
        val status = ProotProbe().probe(File(tmp.root, "libproot.so"))
        assertEquals(ProotStatus.Unavailable(File(tmp.root, "libproot.so").path, ProotFailure.MISSING), status)
    }

    private fun script(body: String): File {
        assumeTrue("needs /bin/sh", File("/bin/sh").canExecute())
        val file = tmp.newFile("libproot.so")
        file.writeText("#!/bin/sh\n$body\n")
        assertTrue(file.setExecutable(true))
        return file
    }

    @Test fun `a working binary reports its version`() {
        val file = script("echo 'proot 5.4.0'; echo more")
        assertEquals(ProotStatus.Found(file.path, "proot 5.4.0"), ProotProbe().probe(file))
    }

    @Test fun `stderr is merged so a version printed there is found`() {
        val file = script("echo 'proot 9.9' 1>&2")
        assertEquals(ProotStatus.Found(file.path, "proot 9.9"), ProotProbe().probe(file))
    }

    @Test fun `the flag passed is --version`() {
        val file = script("echo \"args: \$1\"")
        assertEquals(ProotStatus.Found(file.path, "args: --version"), ProotProbe().probe(file))
    }

    @Test fun `a non-zero exit is unavailable and carries the message`() {
        val file = script("echo 'cannot load library'; exit 3")
        val status = ProotProbe().probe(file) as ProotStatus.Unavailable
        assertEquals(ProotFailure.EXIT_CODE, status.failure)
        assertEquals("exit 3: cannot load library", status.detail)
    }

    @Test fun `a silent binary reports no output`() {
        val status = ProotProbe().probe(script("exit 0")) as ProotStatus.Unavailable
        assertEquals(ProotFailure.NO_OUTPUT, status.failure)
    }

    @Test fun `a hung binary is killed after the timeout`() {
        val file = script("sleep 30")
        val started = System.nanoTime()
        val status = ProotProbe(timeoutMs = 300).probe(file) as ProotStatus.Unavailable
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(ProotFailure.TIMED_OUT, status.failure)
        assertTrue("took ${tookMs}ms", tookMs < 10_000)
    }

    @Test fun `a file that is not executable is unavailable, never an exception`() {
        val file = tmp.newFile("libproot.so").apply { writeText("not a program") }
        file.setExecutable(false)
        val status = ProotProbe().probe(file) as ProotStatus.Unavailable
        assertEquals(ProotFailure.START_FAILED, status.failure)
    }
}
