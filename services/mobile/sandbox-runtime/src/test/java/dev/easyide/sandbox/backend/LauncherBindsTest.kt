package dev.easyide.sandbox.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LauncherBindsTest {

    private val rootfs = File("/data/env/rootfs")
    private val project = File("/data/projects/p1")
    private val ext = GuestBind(File("/data/env/extensions/a.b/1.0.0"), "/opt/easyide/extensions/a.b")

    private fun request(binds: List<GuestBind>) = LaunchRequest(
        rootfs = rootfs,
        hostProjectDir = project,
        guestProjectPath = "/workspace",
        command = listOf("/bin/sh"),
        extraBinds = binds,
    )

    @Test fun `proot emits one -b per extra bind after the project bind`() {
        val argv = ProotLauncher(File("/data/bin/proot")).buildLaunchSpec(request(listOf(ext))).argv

        val projectBind = argv.indexOf("/data/projects/p1:/workspace")
        val extBind = argv.indexOf("/data/env/extensions/a.b/1.0.0:/opt/easyide/extensions/a.b")
        assertTrue(projectBind > 0 && extBind > projectBind)
        assertEquals("-b", argv[extBind - 1])
    }

    @Test fun `no extra binds leaves proot argv unchanged`() {
        val launcher = ProotLauncher(File("/data/bin/proot"))
        val withDefault = launcher.buildLaunchSpec(
            LaunchRequest(rootfs, project, "/workspace", listOf("/bin/sh")),
        )

        assertEquals(withDefault, launcher.buildLaunchSpec(request(emptyList())))
    }

    @Test fun `chroot creates the target inside the rootfs then bind mounts it`() {
        val script = ChrootLauncher().buildLaunchSpec(request(listOf(ext))).argv.last()

        val mkdir = script.indexOf("busybox mkdir -p '/data/env/rootfs/opt/easyide/extensions/a.b'")
        val mount = script.indexOf(
            "busybox mount -o bind '/data/env/extensions/a.b/1.0.0' '/data/env/rootfs/opt/easyide/extensions/a.b'",
        )
        assertTrue(mkdir >= 0 && mount > mkdir && mount < script.indexOf("busybox chroot"))
    }

    @Test fun `binds that proot would split or that escape are unrepresentable`() {
        listOf(
            { GuestBind(File("relative/dir"), "/opt/x") },
            { GuestBind(File("/data/a:b"), "/opt/x") },
            { GuestBind(File("/data/a"), "opt/x") },
            { GuestBind(File("/data/a"), "/opt/x:y") },
            { GuestBind(File("/data/a"), "/opt/../etc") },
            { GuestBind(File("/data/a"), "/opt/./x") },
        ).forEach { make -> assertThrows(IllegalArgumentException::class.java) { make() } }
    }
}
