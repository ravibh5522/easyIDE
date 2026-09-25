package dev.easyide.extensions.view

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewTemplateTest {
    private fun scope(json: String) = ViewScope.of(Json.parseToJsonElement(json))

    private fun template(text: String) = (ViewTemplate.parse(text) as ViewTemplate.Parse.Ok).template

    @Test fun `fields resolve from the item, then the enclosing data`() {
        val root = scope("""{ "title": "Root", "n": 3 }""")
        val item = root.child(Json.parseToJsonElement("""{ "name": "web", "n": 7, "info": { "cpu": 12 }, "ports": [80, 443] }"""))
        assertEquals("web on Root: 7 12 443", template("{name} on {title}: {n} {info.cpu} {ports.1}").resolve(item))
    }

    @Test fun `a missing field renders as empty, never as an error`() {
        assertEquals("[]", template("[{nope}]").resolve(scope("{}")))
        assertEquals("[]", template("[{a.b.c}]").resolve(scope("""{ "a": 1 }""")))
        assertEquals("[]", template("[{a}]").resolve(scope("""{ "a": null }""")))
    }

    @Test fun `braces are escaped by doubling`() {
        assertEquals("{x} 1", template("{{x}} {a}").resolve(scope("""{ "a": 1 }""")))
    }

    @Test fun `malformed templates are refused with an offset`() {
        assertTrue(ViewTemplate.parse("{open") is ViewTemplate.Parse.Error)
        assertTrue(ViewTemplate.parse("close}") is ViewTemplate.Parse.Error)
        assertTrue(ViewTemplate.parse("{}") is ViewTemplate.Parse.Error)
        assertTrue(ViewTemplate.parse("{a b}") is ViewTemplate.Parse.Error)
        assertTrue(ViewTemplate.parse("{a|nope}") is ViewTemplate.Parse.Error)
        assertEquals(3, (ViewTemplate.parse("ab {x") as ViewTemplate.Parse.Error).offset)
    }

    @Test fun `a dollar sign is literal text`() {
        assertEquals("cost: $5", template("cost: $5").resolve(scope("{}")))
    }

    @Test fun `formats`() {
        val s = scope("""{ "b": 1536, "d": 3725, "t": 1000000, "c": 12500, "small": 950 }""")
        assertEquals("1.5 KB", template("{b|bytes}").resolve(s))
        assertEquals("1h 02m", template("{d|duration}").resolve(s))
        assertEquals("12.5k", template("{c|count}").resolve(s))
        assertEquals("950", template("{small|count}").resolve(s))
        assertEquals("15m ago", template("{t|relative}").resolve(s, now = 1_900_000))
    }

    @Test fun `format edge cases`() {
        assertEquals("0 B", ViewFormat.bytes(0.0))
        assertEquals("1.0 TB", ViewFormat.bytes(1024.0 * 1024 * 1024 * 1024))
        assertEquals("45s", ViewFormat.duration(45))
        assertEquals("3m 05s", ViewFormat.duration(185))
        assertEquals("1d 3h", ViewFormat.duration(100_000))
        assertEquals("0s", ViewFormat.duration(-5))
        assertEquals("just now", ViewFormat.relative(1_000, 2_000))
        assertEquals("in 1m", ViewFormat.relative(70_000, 5_000))
        assertEquals("3.4M", ViewFormat.count(3_400_000.0))
        // A value that is not a number is shown as it is.
        assertEquals("abc", template("{a|bytes}").resolve(scope("""{ "a": "abc" }""")))
    }

    @Test fun `a single unformatted field keeps its json type in values`() {
        val s = scope("""{ "n": 5, "flag": true, "name": "x", "list": [1, 2] }""")
        val v = ViewValue.of(Json.parseToJsonElement("""{ "count": "{n}", "on": "{flag}", "label": "id-{name}", "items": "{list}", "lit": 7 }"""), { _, _ -> error("valid") })
        assertEquals(Json.parseToJsonElement("""{ "count": 5, "on": true, "label": "id-x", "items": [1, 2], "lit": 7 }"""), v.resolve(s))
        assertEquals(1, v.templates().count { it.single != null && it.single!!.path == "n" })
    }

    @Test fun `a value with a missing single field resolves to text, not null`() {
        val v = ViewValue.of(JsonPrimitive("{gone}"), { _, _ -> error("valid") })
        assertEquals(JsonPrimitive(""), v.resolve(scope("{}")))
    }

    @Test fun `malformed leaves in a value are reported with their pointer and kept literal`() {
        val errors = ArrayList<String>()
        val v = ViewValue.of(Json.parseToJsonElement("""{ "a": ["{bad"] }"""), { p, _ -> errors += p })
        assertEquals(listOf("/a/0"), errors)
        assertNull(v.templates().singleOrNull())
    }
}
