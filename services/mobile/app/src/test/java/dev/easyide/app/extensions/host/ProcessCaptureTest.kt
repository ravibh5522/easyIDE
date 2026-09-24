package dev.easyide.app.extensions.host

import dev.easyide.extensions.action.ExecOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessCaptureTest {

    private fun sh(script: String) = ProcessBuilder("/bin/sh", "-c", script).start()

    @Test fun `stdout and stderr stay separate`() = runBlocking {
        val r = ProcessCapture.run(sh("echo out; echo err >&2; exit 3"), 1024, 10_000, keep = true, Dispatchers.IO) as ExecOutcome.Exited
        assertEquals(3, r.exitCode)
        assertEquals("out\n", r.stdout)
        assertEquals("err\n", r.stderr)
        assertEquals(false, r.truncated)
    }

    @Test fun `output past the cap is cut and flagged`() = runBlocking {
        val r = ProcessCapture.run(sh("head -c 5000 /dev/zero | tr '\\0' a"), 100, 10_000, keep = true, Dispatchers.IO) as ExecOutcome.Exited
        assertEquals(100, r.stdout.length)
        assertTrue(r.truncated)
    }

    @Test fun `a process past its timeout is killed`() = runBlocking {
        val p = sh("echo started >&2; sleep 30")
        val r = ProcessCapture.run(p, 1024, 300, keep = true, Dispatchers.IO)
        assertTrue(r is ExecOutcome.TimedOut)
        assertTrue(!p.isAlive)
    }

    @Test fun `silent mode keeps no streams`() = runBlocking {
        val r = ProcessCapture.run(sh("echo hi"), 1024, 10_000, keep = false, Dispatchers.IO) as ExecOutcome.Exited
        assertEquals("", r.stdout)
    }
}
