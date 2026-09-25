package dev.easyide.sandbox.bootstrap

import dev.easyide.sandbox.download.Sha256
import dev.easyide.sandbox.download.TestFileServer
import dev.easyide.sandbox.model.RootfsArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

class RootfsProvisionerTest {

    @get:Rule val temp = TemporaryFolder()

    private val provisioner = RootfsProvisioner(Dispatchers.IO)
    private var server: TestFileServer? = null

    @After fun tearDown() { server?.close() }

    /** A ustar archive of regular files, gzipped: just enough for [TarGzExtractor]. */
    private fun tarGz(files: Map<String, ByteArray>): ByteArray {
        val tar = ByteArrayOutputStream()
        for ((name, content) in files) {
            val header = ByteArray(512)
            name.toByteArray().copyInto(header)
            "0000644".toByteArray().copyInto(header, 100)
            content.size.toString(8).padStart(11, '0').toByteArray().copyInto(header, 124)
            header[156] = '0'.code.toByte()
            tar.write(header)
            tar.write(content)
            tar.write(ByteArray((512 - content.size % 512) % 512))
        }
        tar.write(ByteArray(1024))
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(tar.toByteArray()) } }.toByteArray()
    }

    private fun serve(body: ByteArray): RootfsArchive {
        val served = TestFileServer(body).also { server = it }
        return RootfsArchive(served.url, Sha256.fromDigest(Sha256.newDigest().digest(body)))
    }

    private val rootfs get() = File(temp.root, "rootfs")
    private val archive get() = File(temp.root, "cache/image.tar.gz")

    @Test fun `reports download and extraction progress and produces a rootfs`() = runBlocking {
        val body = tarGz(mapOf("bin/sh" to "#!".toByteArray(), "etc/passwd" to "root:x:0:0\n".toByteArray()))
        val events = mutableListOf<InstallEvent>()

        provisioner.provision(serve(body), rootfs, archive, onProgress = {}, onEvent = { events += it }).getOrThrow()

        val downloads = events.filterIsInstance<InstallEvent.Downloading>()
        assertEquals(body.size.toLong(), downloads.last().bytes)
        assertEquals(body.size.toLong(), downloads.last().totalBytes)
        assertEquals(0L, downloads.first().resumedFrom)
        val extractions = events.filterIsInstance<InstallEvent.Extracting>()
        assertEquals(extractions.last().totalBytes, extractions.last().bytesRead)
        assertTrue(events.indexOf(downloads.last()) < events.indexOf(extractions.first()))
        assertTrue(File(rootfs, "bin/sh").isFile)
    }

    @Test fun `a corrupt archive leaves no rootfs behind`() = runBlocking {
        val junk = Random(1).nextBytes(4096)

        val result = provisioner.provision(serve(junk), rootfs, archive, onProgress = {})

        assertTrue(result.isFailure)
        assertFalse(rootfs.exists())
    }

    @Test fun `cancelling mid-extraction removes the half-unpacked rootfs and keeps the download`() = runBlocking {
        val big = Random(2).nextBytes(3 * 1024 * 1024)
        val body = tarGz(mapOf("bin/sh" to "#!".toByteArray(), "usr/big.bin" to big))
        val source = serve(body)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var running: kotlinx.coroutines.Deferred<Result<Unit>>
        running = async(Dispatchers.Default) {
            provisioner.provision(source, rootfs, archive, onProgress = {}, onEvent = { event ->
                if (event is InstallEvent.Extracting && cancelled.compareAndSet(false, true)) {
                    running.cancel()
                }
            })
        }

        running.join()

        assertTrue(running.isCancelled)
        assertFalse(rootfs.exists())
        assertTrue(archive.isFile)
    }
}
