package dev.easyide.app.ui.screens.onboarding

import dev.easyide.sandbox.SandboxError
import dev.easyide.sandbox.bootstrap.InstallEvent
import dev.easyide.sandbox.download.DownloadError
import dev.easyide.sandbox.download.Sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class InstallProgressTest {

    private fun downloading(bytes: Long, total: Long?) = InstallEvent.Downloading(bytes, total, resumedFrom = 0)

    @Test fun `phases cover zero to one in order with setup`() {
        val f = { e: InstallEvent -> overallFraction(e, hasSetup = true)!! }
        assertEquals(0f, f(InstallEvent.PreparingRuntime), 0.001f)
        assertEquals(0.02f, f(downloading(0, 100)), 0.001f)
        assertEquals(0.55f, f(downloading(100, 100)), 0.001f)
        assertEquals(0.55f, f(InstallEvent.Extracting(0, 10)), 0.001f)
        assertEquals(0.80f, f(InstallEvent.Extracting(10, 10)), 0.001f)
        assertEquals(0.80f, f(InstallEvent.Setup(1, 2, "apt-get update")), 0.001f)
        assertEquals(0.90f, f(InstallEvent.Setup(2, 2, "apt-get install")), 0.001f)
    }

    @Test fun `a bare image spends the whole tail on extraction`() {
        assertEquals(1f, overallFraction(InstallEvent.Extracting(10, 10), hasSetup = false)!!, 0.001f)
        assertEquals(0.65f, overallFraction(downloading(50, 50), hasSetup = false)!!, 0.001f)
    }

    @Test fun `unknown download size gives no fraction and oversized counts are clamped`() {
        assertNull(overallFraction(downloading(500, null), hasSetup = true))
        assertNull(overallFraction(downloading(500, 0), hasSetup = true))
        assertEquals(0.55f, overallFraction(downloading(999, 100), hasSetup = true)!!, 0.001f)
    }

    @Test fun `the bar never runs backwards and keeps its value when a phase is unknowable`() {
        assertEquals(0.5f, nextFraction(0.5f, downloading(1, 100), hasSetup = true)!!, 0.001f)
        assertEquals(0.5f, nextFraction(0.5f, downloading(5, null), hasSetup = true)!!, 0.001f)
        assertEquals(0.55f, nextFraction(null, InstallEvent.Extracting(0, 10), hasSetup = true)!!, 0.001f)
        assertNull(nextFraction(null, downloading(5, null), hasSetup = true))
    }

    private fun sha() = Sha256.parse("0".repeat(64))

    @Test fun `download errors are classified through the provisioner's wrapper`() {
        fun wrapped(cause: Throwable) = SandboxError.ProvisioningFailed("env", "x", cause)

        assertEquals(InstallFailure.NETWORK, classifyInstallFailure(wrapped(DownloadError.Network("u", IOException("reset")))).kind)
        val http = classifyInstallFailure(wrapped(DownloadError.HttpStatus("u", 503)))
        assertEquals(InstallFailure.SERVER, http.kind)
        assertEquals("HTTP 503", http.detail)
        assertEquals(InstallFailure.CORRUPT_DOWNLOAD, classifyInstallFailure(wrapped(DownloadError.Integrity("u", sha(), sha()))).kind)
        assertEquals(InstallFailure.CORRUPT_DOWNLOAD, classifyInstallFailure(wrapped(DownloadError.TooLarge("u", 1))).kind)
    }

    @Test fun `storage failures distinguish a full disk`() {
        assertEquals(
            InstallFailure.STORAGE_FULL,
            classifyInstallFailure(DownloadError.Storage("f", IOException("No space left on device"))).kind,
        )
        assertEquals(InstallFailure.STORAGE, classifyInstallFailure(DownloadError.Storage("f", IOException("denied"))).kind)
        assertEquals(InstallFailure.STORAGE_FULL, classifyInstallFailure(IOException("write failed: No space left on device")).kind)
    }

    @Test fun `unsupported devices are not retryable`() {
        val abi = classifyInstallFailure(SandboxError.ProvisioningFailed("e", "Ubuntu has no rootfs for mips"))
        assertEquals(InstallFailure.UNSUPPORTED_DEVICE, abi.kind)
        assertFalse(abi.kind.retryable)
        assertEquals(InstallFailure.UNSUPPORTED_DEVICE, classifyInstallFailure(SandboxError.BackendUnavailable("proot", "no binary")).kind)
        assertTrue(InstallFailure.entries.filter { it != InstallFailure.UNSUPPORTED_DEVICE }.all { it.retryable })
    }

    @Test fun `a failed setup command is named and anything else is unknown`() {
        val setup = classifyInstallFailure(
            SandboxError.ProvisioningFailed("e", "setup step 'apt-get install x' failed with exit 100 after 3 attempts"),
        )
        assertEquals(InstallFailure.SETUP_STEP, setup.kind)
        assertNotNull(setup.detail)
        assertTrue(setup.detail!!.startsWith("setup step 'apt-get install x'"))

        val other = classifyInstallFailure(IllegalStateException("boom"))
        assertEquals(InstallFailure.UNKNOWN, other.kind)
        assertEquals("boom", other.detail)
    }
}
