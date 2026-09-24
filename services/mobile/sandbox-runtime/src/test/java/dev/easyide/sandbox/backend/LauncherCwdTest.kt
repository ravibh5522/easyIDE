package dev.easyide.sandbox.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LauncherCwdTest {

    private val rootfs = File("/data/env/rootfs")
    private val project = File("/data/projects/p1")

    private fun request(cwd: String?, project: File? = this.project) = LaunchRequest(
        rootfs = rootfs,
        hostProjectDir = project,
        guestProjectPath = "/workspace",
        command = listOf("git", "log", "-1"),
        guestCwd = cwd,
    )

    private fun prootCwd(request: LaunchRequest): String {
        val argv = ProotLauncher(File("/data/bin/proot")).buildLaunchSpec(request).argv
        return argv[argv.indexOf("-w") + 1]
    }

    @Test fun `proot starts in the requested guest directory`() {
        assertEquals("/workspace/src", prootCwd(request("/workspace/src")))
    }

    @Test fun `proot defaults to the project mount, then the guest home`() {
        assertEquals("/workspace", prootCwd(request(null)))
        assertEquals("/root", prootCwd(request(null, project = null)))
    }

    @Test fun `proot argv ends with the command unchanged`() {
        val argv = ProotLauncher(File("/data/bin/proot")).buildLaunchSpec(request("/workspace")).argv
        assertEquals(listOf("git", "log", "-1"), argv.takeLast(3))
    }

    @Test fun `chroot changes directory through the guest shell and keeps argv words intact`() {
        val script = ChrootLauncher().buildLaunchSpec(request("/workspace/a b")).argv.last()
        assertTrue(script.contains("'/bin/sh' '-c' 'cd \"\$0\" && exec \"\$@\"' '/workspace/a b' 'git' 'log' '-1'"))
    }

    @Test fun `a relative guest cwd is unrepresentable`() {
        assertThrows(IllegalArgumentException::class.java) { request("workspace") }
    }
}
