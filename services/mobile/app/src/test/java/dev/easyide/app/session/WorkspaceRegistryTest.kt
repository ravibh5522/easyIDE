package dev.easyide.app.session

import android.content.ComponentCallbacks2
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class WorkspaceRegistryTest {

    private class Fake(val id: String, val gate: CompletableDeferred<Unit>? = null) : HeldSession {
        val events = mutableListOf<String>()
        var ended: Boolean? = null
        var discarded: Boolean? = null

        override suspend fun saveBackup() {
            gate?.await()
            events += "save"
        }
        override fun onParked() { events += "parked" }
        override fun onResumed() { events += "resumed" }
        override fun end(discardStored: Boolean) {
            ended = true
            discarded = discardStored
            events += "end"
        }
    }

    private var now = 0L
    private var limit = 2
    private val created = mutableListOf<Fake>()
    private val settledSeen = mutableMapOf<String, Job?>()
    private val scope = TestScope(UnconfinedTestDispatcher())

    private val registry = WorkspaceRegistry<Fake>(scope, { ++now }, { limit }) { id, _, settled ->
        settledSeen[id] = settled
        Fake(id).also { created += it }
    }

    private fun open(id: String, env: String = "env") = registry.obtain(id, env)

    @Test fun `obtain creates once and returns the same session until it ends`() {
        val a = open("a")
        assertSame(a, open("a"))
        assertEquals(1, created.size)
        assertEquals(setOf("a"), registry.liveIds.value)
    }

    @Test fun `obtaining does not attach, attaching resumes`() {
        val a = open("a")
        assertEquals(emptyList<String>(), a.events)
        assertNotNull(registry.attach("a"))
        assertEquals(listOf("resumed"), a.events)
    }

    @Test fun `attaching a second project parks the first and saves it`() {
        val a = open("a")
        registry.attach("a")
        val b = open("b")
        registry.attach("b")
        assertEquals(listOf("resumed", "parked", "save"), a.events)
        assertEquals(listOf("resumed"), b.events)
        assertEquals(setOf("a", "b"), registry.liveIds.value)
    }

    @Test fun `leaving parks and re-entering resumes the same session`() {
        val a = open("a")
        val lease = registry.attach("a")!!
        registry.park("a", lease)
        assertEquals(listOf("resumed", "parked", "save"), a.events)
        assertSame(a, open("a"))
        registry.attach("a")
        assertEquals("resumed", a.events.last())
        assertTrue(a.ended == null)
    }

    @Test fun `a stale screen cannot park the workspace its replacement re-attached`() {
        val a = open("a")
        val first = registry.attach("a")!!
        val second = registry.attach("a")!!
        registry.park("a", first)
        assertEquals(listOf("resumed", "resumed"), a.events)
        registry.park("a", second)
        assertEquals("save", a.events.last())
    }

    @Test fun `over the park limit the least recently used parked one is saved then ended without discarding`() {
        limit = 1
        val a = open("a"); registry.attach("a")
        val b = open("b"); registry.attach("b")
        val c = open("c"); registry.attach("c")
        // a and b are parked, one too many: a was left first.
        assertEquals(true, a.ended)
        assertEquals(false, a.discarded)
        assertEquals(listOf("resumed", "parked", "save", "save", "end"), a.events)
        assertNull(b.ended)
        assertEquals(setOf("b", "c"), registry.liveIds.value)
    }

    @Test fun `a park limit of zero ends a workspace as soon as it is left`() {
        limit = 0
        val a = open("a")
        val lease = registry.attach("a")!!
        registry.park("a", lease)
        assertEquals(true, a.ended)
        assertEquals(emptySet<String>(), registry.liveIds.value)
    }

    @Test fun `close ends immediately and discards what was stored`() {
        val a = open("a")
        registry.attach("a")
        registry.close("a")
        assertEquals(true, a.discarded)
        assertEquals(emptySet<String>(), registry.liveIds.value)
        registry.close("a")
    }

    @Test fun `memory pressure ends parked workspaces by policy and never the active one`() {
        limit = 8
        val a = open("a"); registry.attach("a")
        val b = open("b"); registry.attach("b")
        val c = open("c"); registry.attach("c")
        registry.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(true, a.ended)
        assertNull(b.ended)
        registry.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertEquals(true, b.ended)
        assertNull(c.ended)
        assertEquals(setOf("c"), registry.liveIds.value)
    }

    @Test fun `an environment change ends the old session and starts a new one`() {
        val old = open("a", "env1")
        registry.attach("a")
        val fresh = open("a", "env2")
        assertTrue(old !== fresh)
        assertEquals(false, old.discarded)
        assertEquals(setOf("a"), registry.liveIds.value)
    }

    @Test fun `a new session waits for the previous one's final save`() {
        val gate = CompletableDeferred<Unit>()
        val gated = WorkspaceRegistry<Fake>(scope, { ++now }, { limit }) { id, _, settled ->
            settledSeen[id] = settled
            Fake(id, if (created.isEmpty()) gate else null).also { created += it }
        }
        val first = gated.obtain("a", "env")
        gated.attach("a")
        gated.endAll()
        assertNull(first.ended)

        val second = gated.obtain("a", "env")
        val waiting = settledSeen["a"]!!
        assertTrue(second !== first)
        assertTrue(waiting.isActive)

        gate.complete(Unit)
        assertTrue(waiting.isCompleted)
        assertEquals(true, first.ended)
        assertEquals(false, first.discarded)
        // Nothing left to wait for afterwards.
        gated.close("a")
        gated.obtain("a", "env")
        assertNull(settledSeen["a"])
    }

    @Test fun `flushAll saves every live session`() = runTest {
        val a = open("a")
        val b = open("b")
        registry.flushAll()
        assertEquals(listOf("save"), a.events)
        assertEquals(listOf("save"), b.events)
    }

    @Test fun `attaching a project that is not live returns no lease`() {
        assertNull(registry.attach("missing"))
    }
}
