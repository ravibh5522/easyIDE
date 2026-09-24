package dev.easyide.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class DiskUsageTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `a missing path is zero`() {
        assertEquals(0L, DiskUsage.bytesOf(File(tmp.root, "absent")))
        assertEquals(0L, DiskUsage.bytesOfContents(File(tmp.root, "absent")))
    }

    @Test fun `an empty directory is zero`() {
        assertEquals(0L, DiskUsage.bytesOf(tmp.newFolder("empty")))
    }

    @Test fun `regular files are summed recursively`() {
        val dir = tmp.newFolder("tree")
        writeBytes(File(dir, "a"), 10)
        writeBytes(File(dir, "sub/b"), 20)
        writeBytes(File(dir, "sub/deeper/c"), 30)
        assertEquals(60L, DiskUsage.bytesOf(dir))
    }

    @Test fun `a single file is its own size`() {
        assertEquals(7L, DiskUsage.bytesOf(writeBytes(File(tmp.root, "f"), 7)))
    }

    @Test fun `symlinks are not followed and count as zero`() {
        val precious = tmp.newFolder("precious")
        writeBytes(File(precious, "big"), 1000)
        val bigFile = writeBytes(File(tmp.root, "outside-file"), 500)

        val rootfs = tmp.newFolder("rootfs")
        writeBytes(File(rootfs, "own"), 10)
        Files.createSymbolicLink(File(rootfs, "dir-link").toPath(), precious.toPath())
        Files.createSymbolicLink(File(rootfs, "file-link").toPath(), bigFile.toPath())
        Files.createSymbolicLink(File(rootfs, "dangling").toPath(), File(tmp.root, "nowhere").toPath())

        assertEquals(10L, DiskUsage.bytesOf(rootfs))
    }

    @Test fun `a symlink measured directly is zero`() {
        val precious = tmp.newFolder("precious")
        writeBytes(File(precious, "big"), 1000)
        val link = File(tmp.root, "link")
        Files.createSymbolicLink(link.toPath(), precious.toPath())
        assertEquals(0L, DiskUsage.bytesOf(link))
    }

    @Test fun `contents skip the names that are kept`() {
        val dir = tmp.newFolder("archives")
        writeBytes(File(dir, "a.deb"), 100)
        writeBytes(File(dir, "lock"), 1)
        writeBytes(File(dir, "partial/p"), 50)
        assertEquals(151L, DiskUsage.bytesOfContents(dir))
        assertEquals(100L, DiskUsage.bytesOfContents(dir) { it == "lock" || it == "partial" })
    }
}
