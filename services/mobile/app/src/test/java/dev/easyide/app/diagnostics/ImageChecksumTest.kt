package dev.easyide.app.diagnostics

import dev.easyide.app.data.SandboxImages
import dev.easyide.sandbox.SandboxPaths
import dev.easyide.sandbox.download.Sha256
import dev.easyide.sandbox.model.RootfsArchive
import dev.easyide.sandbox.model.SandboxImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageChecksumTest {
    @get:Rule val tmp = TemporaryFolder()

    private val pinned = Sha256.parse("a".repeat(64))
    private val different = Sha256.parse("b".repeat(64))

    @Test fun `classification covers every combination`() {
        assertEquals(ChecksumStatus.NO_PINNED_ROOTFS, ImageChecksum.classify(null, archivePresent = true, actual = pinned))
        assertEquals(ChecksumStatus.NO_PINNED_ROOTFS, ImageChecksum.classify(null, archivePresent = false, actual = null))
        assertEquals(ChecksumStatus.ARCHIVE_REMOVED, ImageChecksum.classify(pinned, archivePresent = false, actual = null))
        assertEquals(ChecksumStatus.NOT_CHECKED, ImageChecksum.classify(pinned, archivePresent = true, actual = null))
        assertEquals(ChecksumStatus.VERIFIED, ImageChecksum.classify(pinned, archivePresent = true, actual = pinned))
        assertEquals(ChecksumStatus.MISMATCH, ImageChecksum.classify(pinned, archivePresent = true, actual = different))
    }

    @Test fun `an absent archive is never called verified`() {
        assertEquals(ChecksumStatus.ARCHIVE_REMOVED, ImageChecksum.classify(pinned, archivePresent = false, actual = pinned))
    }

    @Test fun `the cache id matches the runtime's derivation for the catalog urls`() {
        assertEquals(
            "ubuntu-base-24.04.3-base-arm64",
            ImageChecksum.archiveIdFor("https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"),
        )
        assertEquals("a_b_c", ImageChecksum.archiveIdFor("https://h/a b!c.tar.gz"))
        assertEquals("rootfs", ImageChecksum.archiveIdFor("https://h/.tar.gz"))
        assertEquals("rootfs", ImageChecksum.archiveIdFor("https://h/"))
        assertEquals("x.tar", ImageChecksum.archiveIdFor("https://h/x.tar"))
    }

    @Test fun `the catalog image resolves to the pin and the cache file of the device abi`() {
        val paths = SandboxPaths(tmp.root)
        val image = SandboxImages.byId("ubuntu-24.04")
        val found = ImageChecksum.pinnedFor(image, listOf("x86_64"), paths)
        assertNotNull(found)
        assertEquals(image.rootfsByAbi.getValue("x86_64").sha256, found!!.digest)
        assertEquals(paths.cachedImage("ubuntu-base-24.04.3-base-amd64"), found.archive)
    }

    @Test fun `the first supported abi with a rootfs wins, and none means no pin`() {
        val paths = SandboxPaths(tmp.root)
        val image = SandboxImages.byId("ubuntu-24.04")
        val armFirst = ImageChecksum.pinnedFor(image, listOf("armeabi-v7a", "arm64-v8a", "x86_64"), paths)
        assertEquals(paths.cachedImage("ubuntu-base-24.04.3-base-arm64"), armFirst!!.archive)
        assertNull(ImageChecksum.pinnedFor(image, listOf("mips"), paths))
        assertEquals(ChecksumStatus.NO_PINNED_ROOTFS, ImageChecksum.verify(null))
        assertEquals(ChecksumStatus.NO_PINNED_ROOTFS, ImageChecksum.quickStatus(null))
    }

    private fun imageFor(file: File?): Pair<SandboxImage, SandboxPaths> {
        val paths = SandboxPaths(tmp.root)
        val digest = file?.let { Sha256.of(it) } ?: pinned
        val image = SandboxImage(
            id = "test",
            label = "Test",
            description = "",
            rootfsByAbi = mapOf("x86_64" to RootfsArchive("https://h/base.tar.gz", digest)),
        )
        return image to paths
    }

    @Test fun `hashing the cached archive verifies it, and a changed archive is a mismatch`() {
        val (_, paths) = imageFor(null)
        val cached = paths.cachedImage("base")
        writeBytes(cached, 1000)
        val (image, _) = imageFor(cached)
        val pin = ImageChecksum.pinnedFor(image, listOf("x86_64"), paths)

        assertEquals(ChecksumStatus.NOT_CHECKED, ImageChecksum.quickStatus(pin))
        assertEquals(ChecksumStatus.VERIFIED, ImageChecksum.verify(pin))

        cached.appendText("tampered")
        assertEquals(ChecksumStatus.MISMATCH, ImageChecksum.verify(pin))

        cached.delete()
        assertEquals(ChecksumStatus.ARCHIVE_REMOVED, ImageChecksum.verify(pin))
        assertEquals(ChecksumStatus.ARCHIVE_REMOVED, ImageChecksum.quickStatus(pin))
    }
}
