package dev.easyide.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class OsReleaseTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ubuntu = """
        PRETTY_NAME="Ubuntu 24.04.3 LTS"
        NAME="Ubuntu"
        VERSION_ID="24.04"
        VERSION="24.04.3 LTS (Noble Numbat)"
        ID=ubuntu
        ID_LIKE=debian
    """.trimIndent()

    @Test fun `parse reads quoted and bare values`() {
        val fields = OsRelease.parse(ubuntu)
        assertEquals("Ubuntu 24.04.3 LTS", fields["PRETTY_NAME"])
        assertEquals("ubuntu", fields["ID"])
        assertEquals("debian", fields["ID_LIKE"])
    }

    @Test fun `parse skips comments, blank lines and lines without an equals sign`() {
        val fields = OsRelease.parse("# comment\n\nNAME='Debian GNU/Linux'\nnot a pair\n  VERSION_ID = \"12\"  \n")
        assertEquals(mapOf("NAME" to "Debian GNU/Linux", "VERSION_ID" to "12"), fields)
    }

    @Test fun `parse unescapes what a double-quoted value may escape`() {
        assertEquals("say \"hi\" \\ \$x", OsRelease.parse("""PRETTY_NAME="say \"hi\" \\ \${'$'}x"""")["PRETTY_NAME"])
    }

    @Test fun `the pretty name wins`() {
        assertEquals("Ubuntu 24.04.3 LTS", OsRelease.displayName(ubuntu))
    }

    @Test fun `without a pretty name it is the name and version id`() {
        assertEquals("Alpine Linux 3.20", OsRelease.displayName("NAME=\"Alpine Linux\"\nVERSION_ID=3.20\n"))
        assertEquals("Arch Linux", OsRelease.displayName("NAME=\"Arch Linux\"\n"))
    }

    @Test fun `a blank pretty name falls back`() {
        assertEquals("Foo 1", OsRelease.displayName("PRETTY_NAME=\"\"\nNAME=Foo\nVERSION_ID=1\n"))
    }

    @Test fun `nothing usable is null`() {
        assertNull(OsRelease.displayName(""))
        assertNull(OsRelease.displayName("ID=ubuntu\nVERSION_ID=24.04\n"))
    }

    @Test fun `read finds etc os-release`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/os-release").writeText(ubuntu)
        assertEquals("Ubuntu 24.04.3 LTS", OsRelease.read(rootfs))
    }

    @Test fun `read follows the relative symlink Ubuntu uses`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "usr/lib").mkdirs()
        File(rootfs, "etc").mkdirs()
        File(rootfs, "usr/lib/os-release").writeText(ubuntu)
        Files.createSymbolicLink(File(rootfs, "etc/os-release").toPath(), Paths.get("../usr/lib/os-release"))
        assertEquals("Ubuntu 24.04.3 LTS", OsRelease.read(rootfs))
    }

    @Test fun `read falls back to usr lib when etc has none`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "usr/lib").mkdirs()
        File(rootfs, "usr/lib/os-release").writeText("NAME=Foo\nVERSION_ID=2\n")
        assertEquals("Foo 2", OsRelease.read(rootfs))
    }

    @Test fun `a missing rootfs or file is unknown`() {
        assertNull(OsRelease.read(File(tmp.root, "absent")))
        assertNull(OsRelease.read(tmp.newFolder("empty")))
    }

    @Test fun `a dangling symlink is unknown`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "etc").mkdirs()
        Files.createSymbolicLink(File(rootfs, "etc/os-release").toPath(), Paths.get("/nonexistent/os-release"))
        assertNull(OsRelease.read(rootfs))
    }

    @Test fun `an oversized file is not read`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/os-release").writeText("PRETTY_NAME=x\n" + "#".repeat(20_000))
        assertNull(OsRelease.read(rootfs))
    }
}
