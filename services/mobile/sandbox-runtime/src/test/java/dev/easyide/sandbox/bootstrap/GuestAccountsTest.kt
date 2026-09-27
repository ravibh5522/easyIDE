package dev.easyide.sandbox.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GuestAccountsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val entry = "dev:x:1000:0:dev:/root:/bin/bash"

    @Test fun `the default account is uid 1000 in the root group and replaces an older dev line`() {
        val out = GuestAccounts.withDefaultUser(listOf("root:x:0:0:root:/root:/bin/bash", "dev:x:0:0:dev:/root:/bin/bash", "nobody:x:65534:65534::/:/usr/sbin/nologin"), entry)
        assertEquals(listOf("root:x:0:0:root:/root:/bin/bash", "nobody:x:65534:65534::/:/usr/sbin/nologin", entry), out)
    }

    @Test fun `adding the default account twice changes nothing`() {
        val once = GuestAccounts.withDefaultUser(listOf("root:x:0:0:root:/root:/bin/bash"), entry)
        assertEquals(once, GuestAccounts.withDefaultUser(once, entry))
    }

    @Test fun `root and dev get the default password when none is set`() {
        val out = GuestAccounts.withDefaultPasswords(listOf("root:*:19000:0:99999:7:::", "dev:!:::::::"))
        assertEquals(2, out.size)
        assertTrue(out.all { it.split(':')[1] == GuestAccounts.DEFAULT_PASSWORD_HASH })
        assertTrue(out.first().startsWith("dev:"))
    }

    @Test fun `a password the user chose is never replaced`() {
        val mine = "root:\$6\$mysalt\$abc:19000:0:99999:7:::"
        val dev = "dev:\$6\$other\$def:19000:0:99999:7:::"
        val out = GuestAccounts.withDefaultPasswords(listOf(mine, dev))
        assertEquals(listOf(mine, dev), out)
    }

    @Test fun `ensure writes accounts and login profile and is idempotent`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
        File(rootfs, "etc/shadow").writeText("root:*:19000:0:99999:7:::\n")
        File(rootfs, "etc/group").writeText("root:x:0:\ndev:x:1000:\n")
        File(rootfs, "etc/skel").mkdirs()
        File(rootfs, "etc/skel/.bashrc").writeText("# bashrc\n")

        GuestAccounts.ensure(rootfs)
        val first = listOf("etc/passwd", "etc/shadow", "etc/group", "etc/profile.d/easyide.sh", "root/.bashrc").map { File(rootfs, it).readText() }
        GuestAccounts.ensure(rootfs)
        val second = listOf("etc/passwd", "etc/shadow", "etc/group", "etc/profile.d/easyide.sh", "root/.bashrc").map { File(rootfs, it).readText() }

        assertEquals(first, second)
        assertTrue(first[0].contains("dev:x:1000:0:"))
        assertTrue(first[1].contains(GuestAccounts.DEFAULT_PASSWORD_HASH))
        assertEquals("root:x:0:\n", first[2])
        assertTrue(first[3].contains(".opencode/bin"))
    }

    /** A `setpriv` that only runs what follows `--`, so the shim's escalation can be observed without proot. */
    private fun shimEnvironment(): Pair<File, Map<String, String>> {
        val bin = tmp.newFolder("bin")
        File(bin, "setpriv").apply {
            writeText("#!/bin/sh\necho \"escalated: \$*\"\nwhile [ \"\$1\" != -- ]; do shift; done; shift; exec \"\$@\"\n")
            setExecutable(true)
        }
        val script = File(tmp.root, "sudo").apply { writeText(GuestAccounts.SUDO_SHIM); setExecutable(true) }
        return script to mapOf("PATH" to "${bin.path}:${System.getenv("PATH")}")
    }

    @Test fun `without a terminal the shim escalates to uid 0 and runs the command`() {
        val (script, env) = shimEnvironment()
        val builder = ProcessBuilder("sh", script.path, "-u", "root", "echo", "ran").redirectErrorStream(true)
        builder.environment().putAll(env)
        val out = builder.start().let { it.inputStream.bufferedReader().readText().also { _ -> it.waitFor() } }
        assertTrue(out, out.contains("escalated: --reuid=0 --regid=0 --clear-groups"))
        assertTrue(out, out.trim().endsWith("ran"))
    }

    @Test fun `passwd is re-run as uid 0 for the invoking user`() {
        assertTrue(GuestAccounts.PASSWD_SHIM.contains("setpriv --reuid=0"))
    }
}
