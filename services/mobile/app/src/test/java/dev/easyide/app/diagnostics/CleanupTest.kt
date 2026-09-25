package dev.easyide.app.diagnostics

import dev.easyide.sandbox.SandboxPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class CleanupTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "sandbox")
    private val paths get() = SandboxPaths(root)
    private val log get() = AppLog(File(tmp.root, "logs"))
    private val crashes get() = CrashReports(File(tmp.root, "crashes"))
    private val cleanup get() = Cleanup(paths, log, crashes)

    private val rootfs get() = paths.rootfsDir("e1")
    private val precious get() = File(tmp.root, "precious")

    /** A rootfs with apt caches, /tmp, unrelated content, and a link that points at [precious]. */
    private fun populateRootfs() {
        writeBytes(File(rootfs, "var/cache/apt/archives/a.deb"), 100)
        writeBytes(File(rootfs, "var/cache/apt/archives/lock"), 1)
        writeBytes(File(rootfs, "var/cache/apt/archives/partial/p.deb"), 50)
        writeBytes(File(rootfs, "var/lib/apt/lists/x"), 30)
        writeBytes(File(rootfs, "var/lib/apt/lists/partial/y"), 5)
        writeBytes(File(rootfs, "tmp/t"), 7)
        writeBytes(File(rootfs, "tmp/tmux-1000/default"), 1)
        writeBytes(File(rootfs, "etc/keep"), 999)
        writeBytes(File(rootfs, "usr/bin/tool"), 500)
        writeBytes(File(precious, "data"), 4000)
        Files.createSymbolicLink(File(rootfs, "tmp/escape").toPath(), precious.toPath())
        Files.createSymbolicLink(File(rootfs, "var/lib/apt/lists/escape-file").toPath(), File(precious, "data").toPath())
    }

    @Test fun `package cache cleanup frees the apt and tmp contents and nothing else`() {
        populateRootfs()
        val target = Cleanup.Target.EnvironmentPackageCache("e1")

        assertEquals(192L, cleanup.estimate(target))
        val result = cleanup.run(target)

        assertEquals(Cleanup.Result(192, 0), result)
        assertFalse(File(rootfs, "var/cache/apt/archives/a.deb").exists())
        assertFalse(File(rootfs, "var/cache/apt/archives/partial/p.deb").exists())
        assertFalse(File(rootfs, "var/lib/apt/lists/x").exists())
        assertFalse(File(rootfs, "var/lib/apt/lists/partial").exists())
        assertFalse(File(rootfs, "tmp/t").exists())
        // What apt needs to keep working stays.
        assertTrue(File(rootfs, "var/cache/apt/archives/lock").isFile)
        assertTrue(File(rootfs, "var/cache/apt/archives/partial").isDirectory)
        assertTrue(File(rootfs, "var/lib/apt/lists").isDirectory)
        // A live tmux server's socket directory stays.
        assertTrue(File(rootfs, "tmp/tmux-1000/default").isFile)
        // Everything else in the rootfs is untouched.
        assertEquals(999L, File(rootfs, "etc/keep").length())
        assertEquals(500L, File(rootfs, "usr/bin/tool").length())
    }

    @Test fun `links inside the rootfs are removed as links and never followed`() {
        populateRootfs()
        cleanup.run(Cleanup.Target.EnvironmentPackageCache("e1"))

        assertFalse(Files.exists(File(rootfs, "tmp/escape").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(File(rootfs, "var/lib/apt/lists/escape-file").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals(4000L, File(precious, "data").length())
    }

    @Test fun `a rootfs directory that is a link to elsewhere is refused and nothing is deleted`() {
        writeBytes(File(rootfs, "tmp/t"), 7)
        writeBytes(File(precious, "cache/apt/archives/keep.deb"), 4000)
        // /var in the guest is an absolute link, which on the host points at other data.
        Files.createSymbolicLink(File(rootfs, "var").toPath(), precious.toPath())

        try {
            cleanup.run(Cleanup.Target.EnvironmentPackageCache("e1"))
            fail("expected the deletion to be refused")
        } catch (expected: RefusedDeletion) {
            assertTrue(expected.message!!.contains("outside"))
        }
        assertEquals(4000L, File(precious, "cache/apt/archives/keep.deb").length())
        assertTrue(File(rootfs, "tmp/t").exists())
    }

    @Test fun `estimate refuses the same targets`() {
        writeBytes(File(precious, "cache/apt/archives/keep.deb"), 10)
        Files.createDirectories(rootfs.toPath())
        Files.createSymbolicLink(File(rootfs, "var").toPath(), precious.toPath())
        try {
            cleanup.estimate(Cleanup.Target.EnvironmentPackageCache("e1"))
            fail("expected the estimate to be refused")
        } catch (expected: RefusedDeletion) {
            // expected
        }
    }

    @Test fun `a target that is itself a link is treated as empty`() {
        writeBytes(File(precious, "data"), 4000)
        Files.createDirectories(rootfs.toPath())
        Files.createSymbolicLink(File(rootfs, "tmp").toPath(), precious.toPath())
        val result = cleanup.run(Cleanup.Target.EnvironmentPackageCache("e1"))
        assertEquals(0L, result.freedBytes)
        assertEquals(4000L, File(precious, "data").length())
    }

    @Test fun `a missing environment has nothing to free`() {
        val target = Cleanup.Target.EnvironmentPackageCache("never-provisioned")
        assertEquals(0L, cleanup.estimate(target))
        assertEquals(Cleanup.Result(0, 0), cleanup.run(target))
    }

    @Test fun `image archives are deleted and everything else in the sandbox is not`() {
        writeBytes(File(paths.imageCacheDir, "a.tar.gz"), 100)
        writeBytes(File(paths.imageCacheDir, "b.tar.gz.part"), 20)
        writeBytes(File(paths.projectDir("p1"), "src/main.c"), 300)
        writeBytes(File(root, "sessions/s.json"), 40)
        writeBytes(File(precious, "data"), 4000)
        Files.createSymbolicLink(File(paths.imageCacheDir, "escape").toPath(), precious.toPath())

        assertEquals(120L, cleanup.estimate(Cleanup.Target.ImageArchives))
        val result = cleanup.run(Cleanup.Target.ImageArchives)

        assertEquals(Cleanup.Result(120, 0), result)
        assertTrue(paths.imageCacheDir.isDirectory)
        assertEquals(emptyList<String>(), paths.imageCacheDir.list().orEmpty().toList())
        assertEquals(300L, File(paths.projectDir("p1"), "src/main.c").length())
        assertEquals(40L, File(root, "sessions/s.json").length())
        assertEquals(4000L, File(precious, "data").length())
    }

    @Test fun `an image cache directory replaced by a link is left alone`() {
        writeBytes(File(precious, "data"), 4000)
        Files.createDirectories(root.toPath())
        Files.createSymbolicLink(paths.imageCacheDir.toPath(), precious.toPath())
        assertEquals(Cleanup.Result(0, 0), cleanup.run(Cleanup.Target.ImageArchives))
        assertEquals(4000L, File(precious, "data").length())
    }

    @Test fun `clearing logs removes the log and seen crash reports but keeps pending ones`() {
        val log = log
        log.log(LogLevel.INFO, LogSource.APP, "a line")
        val reports = crashes
        val seen = reports.write("seen report", 100)
        reports.acknowledge(seen)
        val pending = reports.write("pending report", 200)
        val logBytes = log.sizeBytes()
        assertTrue(logBytes > 0)
        val seenBytes = "seen report".length.toLong()

        val cleanup = Cleanup(paths, log, reports)
        assertEquals(logBytes + seenBytes, cleanup.estimate(Cleanup.Target.Logs))
        assertEquals(Cleanup.Result(logBytes + seenBytes, 0), cleanup.run(Cleanup.Target.Logs))

        assertEquals(0L, log.sizeBytes())
        assertEquals(listOf(pending), reports.list())
        assertNotNull(reports.pending())
    }

    @Test fun `clearing logs with nothing on disk is a no-op`() {
        assertEquals(Cleanup.Result(0, 0), cleanup.run(Cleanup.Target.Logs))
        assertNull(crashes.pending())
    }

    @Test fun `SafeTree keeps the directory itself and reports what it removed`() {
        val dir = tmp.newFolder("cache")
        writeBytes(File(dir, "a"), 10)
        writeBytes(File(dir, "sub/b"), 20)
        val deleted = SafeTree.deleteContents(dir, tmp.root)
        assertEquals(Deleted(30, 0), deleted)
        assertTrue(dir.isDirectory)
        assertEquals(0, dir.list()!!.size)
    }

    @Test fun `SafeTree refuses a directory outside its base`() {
        val outside = tmp.newFolder("outside")
        writeBytes(File(outside, "f"), 10)
        val base = tmp.newFolder("base")
        try {
            SafeTree.deleteContents(outside, base)
            fail("expected refusal")
        } catch (expected: RefusedDeletion) {
            assertTrue(File(outside, "f").exists())
        }
    }
}
