package dev.easyide.lsp.session

import dev.easyide.lsp.LspPolicy
import dev.easyide.lsp.docs.DocumentStore
import dev.easyide.lsp.protocol.Range
import dev.easyide.lsp.protocol.SyncKind
import dev.easyide.lsp.protocol.SyncOptions
import dev.easyide.lsp.text.LineIndex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DocumentSyncTest {
    private val sent = mutableListOf<Pair<String, JsonElement>>()
    private val store = DocumentStore()
    private val uri = "file:///workspace/main.py"

    private fun sync(options: SyncOptions = INCREMENTAL) =
        DocumentSync(options, { m, p -> sent += m to p }) { it.languageId == "python" }

    private fun methods() = sent.map { it.first }

    /** A model server: applies what it is sent to its own copy. */
    private class ModelServer {
        val docs = HashMap<String, String>()
        val versions = HashMap<String, Int>()

        fun accept(method: String, params: JsonElement) {
            val o = params.jsonObject
            val td = o["textDocument"]!!.jsonObject
            val uri = td["uri"]!!.jsonPrimitive.content
            when (method) {
                "textDocument/didOpen" -> {
                    docs[uri] = td["text"]!!.jsonPrimitive.content
                    versions[uri] = td["version"]!!.jsonPrimitive.int
                }
                "textDocument/didChange" -> {
                    val version = td["version"]!!.jsonPrimitive.int
                    check(version > versions.getValue(uri)) { "version did not increase" }
                    versions[uri] = version
                    for (c in o["contentChanges"]!!.jsonArray) {
                        val co = c.jsonObject
                        val text = co["text"]!!.jsonPrimitive.content
                        val range = Range.fromJson(co["range"])
                        docs[uri] = if (range == null) text else {
                            val old = docs.getValue(uri)
                            val idx = LineIndex(old)
                            old.substring(0, idx.offset(range.start)) + text + old.substring(idx.offset(range.end))
                        }
                    }
                }
                "textDocument/didClose" -> {
                    docs.remove(uri)
                    versions.remove(uri)
                }
            }
        }
    }

    @Test
    fun openChangeFlushSaveCloseInOrder() {
        val s = sync()
        store.open(uri, "python", "print(1)\n")
        s.reconcile(store.snapshots.value)
        store.update(uri, "print(12)\n")
        s.reconcile(store.snapshots.value)
        assertEquals(listOf("textDocument/didOpen"), methods())
        val change = s.flush(uri)!!
        assertEquals(2, change.version)
        store.saved(uri, "print(12)\n")
        s.reconcile(store.snapshots.value)
        store.close(uri)
        s.reconcile(store.snapshots.value)
        assertEquals(listOf("textDocument/didOpen", "textDocument/didChange", "textDocument/didSave", "textDocument/didClose"), methods())
        assertFalse(s.isSynced(uri))
    }

    @Test
    fun incrementalChangeIsOneRange() {
        val s = sync()
        store.open(uri, "python", "a = 1\nb = 2\n")
        s.reconcile(store.snapshots.value)
        store.update(uri, "a = 1\nb = 22\n")
        s.reconcile(store.snapshots.value)
        s.flush(uri)
        val change = sent.last().second.jsonObject["contentChanges"]!!.jsonArray.single().jsonObject
        assertEquals("2", change["text"]!!.jsonPrimitive.content)
        assertEquals(Range.fromJson(change["range"])!!.start.line, 1)
    }

    @Test
    fun saveFlushesPendingChangeFirst() {
        val s = sync()
        store.open(uri, "python", "x")
        s.reconcile(store.snapshots.value)
        store.update(uri, "xy")
        store.saved(uri, "xy")
        s.reconcile(store.snapshots.value)
        assertEquals(listOf("textDocument/didOpen", "textDocument/didChange", "textDocument/didSave"), methods())
    }

    @Test
    fun saveIncludesTextOnlyWhenAsked() {
        val s = sync(INCREMENTAL.copy(saveIncludesText = true))
        store.open(uri, "python", "x")
        s.reconcile(store.snapshots.value)
        store.saved(uri, "x")
        s.reconcile(store.snapshots.value)
        assertEquals("x", sent.last().second.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun otherLanguagesAreNotSynced() {
        val s = sync()
        store.open("file:///workspace/a.go", "go", "package a")
        s.reconcile(store.snapshots.value)
        assertTrue(sent.isEmpty())
        assertEquals(0, s.eligibleCount)
    }

    @Test
    fun closeAndReopenIsANewOpenEvenWhenOnlyTheLatestStateIsSeen() {
        val s = sync()
        store.open(uri, "python", "v1")
        s.reconcile(store.snapshots.value)
        // Close and reopen happen between two reconciles (conflated flow).
        store.close(uri)
        store.open(uri, "python", "v1")
        s.reconcile(store.snapshots.value)
        assertEquals(listOf("textDocument/didOpen", "textDocument/didClose", "textDocument/didOpen"), methods())
    }

    @Test
    fun fullSyncSendsWholeTextAndRefusesOversizedDocuments() {
        val s = sync(SyncOptions(true, SyncKind.FULL, save = true, saveIncludesText = false, willSaveWaitUntil = false))
        store.open(uri, "python", "small")
        s.reconcile(store.snapshots.value)
        store.update(uri, "smaller")
        s.reconcile(store.snapshots.value)
        s.flush(uri)
        assertEquals("smaller", sent.last().second.jsonObject["contentChanges"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        val huge = "x".repeat(LspPolicy.MAX_FULL_SYNC_BYTES / LspPolicy.UTF8_BYTES_PER_UTF16_UNIT + 1)
        store.update(uri, huge)
        s.reconcile(store.snapshots.value)
        assertEquals("textDocument/didClose", sent.last().first)
        assertEquals(setOf(uri), s.oversized)
        store.update(uri, "small again")
        s.reconcile(store.snapshots.value)
        assertEquals("textDocument/didOpen", sent.last().first)
        assertTrue(s.oversized.isEmpty())
    }

    @Test
    fun syncKindNoneSendsNothing() {
        val s = sync(SyncOptions.NONE)
        store.open(uri, "python", "x")
        s.reconcile(store.snapshots.value)
        assertTrue(sent.isEmpty())
        assertEquals(1, s.eligibleCount)
        assertEquals(null, s.versionOf(uri))
    }

    @Test
    fun randomEditsKeepTheServerCopyEqualAndVersionsIncreasing() {
        val s = sync()
        val model = ModelServer()
        val random = Random(SEED)
        var text = "def f():\n    return 1\n"
        store.open(uri, "python", text)
        repeat(ROUNDS) {
            val at = random.nextInt(text.length + 1)
            val cut = (at + random.nextInt(3)).coerceAtMost(text.length)
            text = text.substring(0, at) + listOf("", "x", "\n", "é", "\r\n", "𝄞").random(random) + text.substring(cut)
            store.update(uri, text)
            sent.clear()
            s.reconcile(store.snapshots.value)
            if (random.nextInt(3) == 0) s.flushAll()
            sent.forEach { (m, p) -> model.accept(m, p) }
        }
        sent.clear()
        s.flushAll()
        sent.forEach { (m, p) -> model.accept(m, p) }
        // didOpen happened in the first round's reconcile and was applied then.
        assertEquals(text, model.docs[uri])
    }

    private companion object {
        val INCREMENTAL = SyncOptions(openClose = true, change = SyncKind.INCREMENTAL, save = true, saveIncludesText = false, willSaveWaitUntil = false)
        const val SEED = 11
        const val ROUNDS = 500
    }
}
