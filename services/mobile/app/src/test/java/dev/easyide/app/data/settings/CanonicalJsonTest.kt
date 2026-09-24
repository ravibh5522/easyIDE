package dev.easyide.app.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CanonicalJsonTest {

    @Test
    fun sortsKeysAndDropsWhitespace() {
        assertEquals("{\"a\":[1,\"x\"],\"b\":{\"c\":null,\"d\":true}}", CanonicalJson.encode(json("{ \"b\": {\"d\": true, \"c\": null}, \"a\": [1, \"x\"] }")))
    }

    @Test
    fun formatsNumbersAsEcmaScript() {
        // RFC 8785 appendix B samples.
        assertEquals("0", CanonicalJson.number(-0.0))
        assertEquals("1e+21", CanonicalJson.number(1e21))
        assertEquals("100000000000000000000", CanonicalJson.number(1e20))
        assertEquals("0.000001", CanonicalJson.number(0.000001))
        assertEquals("1e-7", CanonicalJson.number(1e-7))
        assertEquals("333333333.3333333", CanonicalJson.number(333333333.33333329))
        assertEquals("4.5", CanonicalJson.number(4.50))
        assertEquals("-1.5e-10", CanonicalJson.number(-1.5e-10))
        assertEquals("9007199254740992", CanonicalJson.number(9007199254740992.0))
        assertEquals("1", CanonicalJson.encode(json("1.0")))
    }

    @Test
    fun escapesOnlyWhatItMust() {
        assertEquals("\"a\\\"\\\\\\n\\u001fé\"", CanonicalJson.encode(json("\"a\\\"\\\\\\n\\u001fé\"")))
    }

    @Test
    fun hashIsStableUnderReformatting() {
        val a = CanonicalJson.sha256(json("{\"x\": 1.0, \"y\": [\"z\"]}"))
        val b = CanonicalJson.sha256(json("{ \"y\" : [ \"z\" ] , \"x\" : 1 }"))
        assertEquals(a, b)
        assertNotEquals(a, CanonicalJson.sha256(json("{\"x\": 2, \"y\": [\"z\"]}")))
        assertEquals(64, a.length)
    }
}
