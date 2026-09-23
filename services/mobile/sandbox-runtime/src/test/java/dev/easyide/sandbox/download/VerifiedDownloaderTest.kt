package dev.easyide.sandbox.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class VerifiedDownloaderTest {

    @get:Rule val temp = TemporaryFolder()

    private val body = Random(SEED).nextBytes(BODY_SIZE)
    private val digest = Sha256.fromDigest(Sha256.newDigest().digest(body))
    private lateinit var server: TestFileServer
    private lateinit var destination: File
    private lateinit var part: File
    private val downloader = VerifiedDownloader(Dispatchers.IO)

    @Before fun setUp() {
        server = TestFileServer(body)
        destination = File(temp.root, "cache/file.bin")
        part = File(destination.parentFile, "file.bin.part")
    }

    @After fun tearDown() = server.close()

    private fun request(sha256: Sha256 = digest, maxBytes: Long = MAX) =
        DownloadRequest(server.url, sha256, maxBytes, destination)

    private fun run(request: DownloadRequest = request()): List<DownloadEvent> =
        runBlocking { downloader.download(request).toList() }

    private inline fun <reified T : DownloadError> assertFails(block: () -> Unit): T {
        try {
            block()
        } catch (e: DownloadError) {
            if (e is T) return e
            fail("expected ${T::class.simpleName}, got $e")
        }
        fail("expected ${T::class.simpleName}, nothing thrown")
        error("unreachable")
    }

    private fun seedPart(bytes: Int) {
        part.parentFile.mkdirs()
        part.writeBytes(body.copyOf(bytes))
    }

    @Test fun `fresh download verifies, renames and reports progress`() {
        val events = run()

        assertArrayEquals(body, destination.readBytes())
        assertFalse(part.exists())
        assertEquals(DownloadEvent.Started(0, BODY_SIZE.toLong()), events.first())
        val done = events.last() as DownloadEvent.Verified
        assertEquals(BODY_SIZE.toLong(), done.bytes)
        assertFalse(done.fromCache)
        val progress = events.filterIsInstance<DownloadEvent.Progress>()
        assertTrue(progress.size > 1)
        assertEquals(BODY_SIZE.toLong(), progress.last().bytes)
        assertEquals(listOf<String?>(null), server.rangeHeaders)
    }

    @Test fun `digest mismatch deletes partial and never creates destination`() {
        val wrong = Sha256.parse("0".repeat(64))

        val error = assertFails<DownloadError.Integrity> { run(request(sha256 = wrong)) }

        assertEquals(wrong, error.expected)
        assertEquals(digest, error.actual)
        assertFalse(destination.exists())
        assertFalse(part.exists())
    }

    @Test fun `resume sends Range and hashes old prefix plus new bytes`() {
        seedPart(RESUME_AT)

        val events = run()

        assertEquals(listOf<String?>("bytes=$RESUME_AT-"), server.rangeHeaders)
        assertEquals(DownloadEvent.Started(RESUME_AT.toLong(), BODY_SIZE.toLong()), events.first())
        assertArrayEquals(body, destination.readBytes())
    }

    @Test fun `server ignoring Range restarts from zero instead of appending`() {
        seedPart(RESUME_AT)
        server.honourRange = false

        run()

        assertEquals(listOf("bytes=$RESUME_AT-", null), server.rangeHeaders)
        assertArrayEquals(body, destination.readBytes())
    }

    @Test fun `206 with wrong Content-Range restarts from zero`() {
        seedPart(RESUME_AT)
        server.wrongRangeStart = 0

        run()

        assertEquals(2, server.requestCount)
        assertArrayEquals(body, destination.readBytes())
    }

    @Test fun `416 for an over-long partial restarts from zero`() {
        part.parentFile.mkdirs()
        part.writeBytes(body + byteArrayOf(1, 2, 3))

        run()

        assertEquals(2, server.requestCount)
        assertArrayEquals(body, destination.readBytes())
    }

    @Test fun `poisoned partial fails integrity and is deleted so the retry starts clean`() {
        part.parentFile.mkdirs()
        part.writeBytes(ByteArray(RESUME_AT) { 7 })

        assertFails<DownloadError.Integrity> { run() }
        assertFalse(part.exists())

        run()
        assertArrayEquals(body, destination.readBytes())
    }

    @Test fun `declared length over the limit fails before writing and drops partial`() {
        seedPart(RESUME_AT)

        val error = assertFails<DownloadError.TooLarge> { run(request(maxBytes = BODY_SIZE - 1L)) }

        assertEquals(BODY_SIZE - 1L, error.limitBytes)
        assertFalse(part.exists())
        assertFalse(destination.exists())
    }

    @Test fun `undeclared length is still capped by bytes received`() {
        server.sendLength = false

        assertFails<DownloadError.TooLarge> { run(request(maxBytes = BODY_SIZE / 2L)) }

        assertFalse(part.exists())
    }

    @Test fun `undeclared length within limit downloads and verifies`() {
        server.sendLength = false

        val events = run()

        assertNull((events.first() as DownloadEvent.Started).totalBytes)
        assertArrayEquals(body, destination.readBytes())
    }

    @Test fun `http error keeps partial for a later resume`() {
        seedPart(RESUME_AT)
        server.forcedStatus = 503

        val error = assertFails<DownloadError.HttpStatus> { run() }

        assertEquals(503, error.code)
        assertEquals(RESUME_AT.toLong(), part.length())
    }

    @Test fun `truncated body is a network error that keeps partial`() {
        server.truncateTo = RESUME_AT

        assertFails<DownloadError.Network> { run() }

        assertFalse(destination.exists())
        val kept = part.length()
        assertTrue(kept > 0)
        server.truncateTo = null
        run()
        assertEquals("bytes=$kept-", server.rangeHeaders.last())
        assertArrayEquals(body, destination.readBytes())
    }

    @Test fun `valid cached destination is verified without any request`() {
        destination.parentFile.mkdirs()
        destination.writeBytes(body)

        val events = run()

        assertEquals(1, events.size)
        assertTrue((events.single() as DownloadEvent.Verified).fromCache)
        assertEquals(0, server.requestCount)
    }

    @Test fun `corrupt cached destination is replaced by a fresh download`() {
        destination.parentFile.mkdirs()
        destination.writeBytes(body.copyOf(BODY_SIZE - 1))

        val done = run().last() as DownloadEvent.Verified

        assertFalse(done.fromCache)
        assertEquals(1, server.requestCount)
        assertArrayEquals(body, destination.readBytes())
    }

    @Test fun `cancelling mid-transfer stops promptly, keeps partial, and the next run resumes`() = runBlocking {
        server.dripDelayMs = DRIP_MS
        val job = launch(Dispatchers.IO) {
            downloader.download(request()).collect { }
        }
        withTimeout(PROMPT_MS) {
            while (!part.exists() || part.length() < CANCEL_AFTER) delay(POLL_MS)
        }

        withTimeout(PROMPT_MS) { job.cancelAndJoinQuietly() }

        assertTrue(job.isCancelled)
        assertFalse(destination.exists())
        val kept = part.length()
        assertTrue(kept in CANCEL_AFTER until BODY_SIZE)

        server.dripDelayMs = null
        run()
        assertEquals("bytes=$kept-", server.rangeHeaders.last())
        assertArrayEquals(body, destination.readBytes())
    }

    @Test fun `flow is cold and a second collection reuses the verified file`() = runBlocking {
        val flow = downloader.download(request())
        assertEquals(0, server.requestCount)

        flow.last()
        val second = flow.filterIsInstance<DownloadEvent.Verified>().first()

        assertTrue(second.fromCache)
        assertEquals(1, server.requestCount)
    }

    private suspend fun Job.cancelAndJoinQuietly() {
        cancel()
        join()
    }

    private companion object {
        const val SEED = 42
        const val BODY_SIZE = 1_500_000
        const val RESUME_AT = 400_000
        const val MAX = 10L * 1024 * 1024
        const val PROMPT_MS = 5_000L
        const val DRIP_MS = 20L
        const val CANCEL_AFTER = 100_000
        const val POLL_MS = 10L
    }
}
