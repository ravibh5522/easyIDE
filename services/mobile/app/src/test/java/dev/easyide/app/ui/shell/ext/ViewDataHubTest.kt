package dev.easyide.app.ui.shell.ext

import dev.easyide.extensions.view.ViewLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewDataHubTest {
    private var now = 0L
    private val hub = ViewDataHub { now }
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject
    private val state = obj("""{ "query": "", "items": [] }""")

    @Test fun `data is the initial state until something writes`() {
        assertEquals(state, hub.data("v", state))
        assertTrue(hub.provide("v", obj("""{ "items": [1] }"""), state))
        assertEquals(obj("""{ "query": "", "items": [1] }"""), hub.data("v", state))
    }

    @Test fun `a provider update merges over typed input and an array becomes the items`() {
        hub.edit("v", state) { JsonObject(it + ("query" to JsonPrimitive("web"))) }
        hub.provide("v", obj("""{ "other": 1 }"""), state)
        assertEquals(JsonPrimitive("web"), hub.data("v", state)["query"])
        now += 1_000
        hub.provide("v", Json.parseToJsonElement("[1, 2]"), state)
        assertEquals(Json.parseToJsonElement("[1, 2]"), hub.data("v", state)["items"])
    }

    @Test fun `updates are limited per key per second and recover after the window`() {
        val accepted = (1..ViewLimits.MAX_UPDATES_PER_SECOND + 5).count { hub.provide("v", obj("""{ "n": $it }"""), state) }
        assertEquals(ViewLimits.MAX_UPDATES_PER_SECOND, accepted)
        assertFalse(hub.provide("v", obj("""{ "n": 99 }"""), state))
        assertTrue(hub.provide("w", obj("""{ "n": 1 }"""), state))
        now += 1_000
        assertTrue(hub.provide("v", obj("""{ "n": 100 }"""), state))
        assertEquals(JsonPrimitive(100), hub.data("v", state)["n"])
    }

    @Test fun `an oversized or non-object update changes nothing`() {
        val big = obj("""{ "x": "${"a".repeat(ViewLimits.MAX_UPDATE_BYTES)}" }""")
        assertFalse(hub.provide("v", big, state))
        assertFalse(hub.provide("v", JsonPrimitive(1), state))
        assertEquals(state, hub.data("v", state))
    }

    @Test fun `local edits are not rate limited and drop forgets a key`() {
        repeat(50) { i -> hub.edit("v", state) { JsonObject(it + ("n" to JsonPrimitive(i))) } }
        assertEquals(JsonPrimitive(49), hub.data("v", state)["n"])
        hub.drop("v")
        assertEquals(state, hub.data("v", state))
    }

    @Test fun `the rate window slides`() {
        val rate = UpdateRate(2, 1_000)
        assertTrue(rate.allow(0)); assertTrue(rate.allow(500)); assertFalse(rate.allow(999))
        assertTrue(rate.allow(1_000)); assertFalse(rate.allow(1_100)); assertTrue(rate.allow(1_500))
    }
}
