package dev.easyide.extensions.registry

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JcsTest {
    private fun canon(text: String) = String(Jcs.canonicalize(Jcs.parse(text)), Charsets.UTF_8)

    @Test fun `members are sorted and whitespace dropped`() {
        assertEquals("""{"a":[1,true,null],"b":{"x":"y","z":0}}""", canon(""" { "b" : { "z":0, "x":"y" }, "a": [ 1 , true , null ] } """))
    }

    @Test fun `keys sort by UTF-16 code units`() {
        // RFC 8785 sec 3.2.3: U+1F600 (surrogates D83D DE00) sorts before U+FB33 despite the higher code point.
        assertEquals("{\"\\r\":0,\"1\":0,\"\u00e9\":0,\"\ud83d\ude00\":0,\"\ufb33\":0}", canon("{\"\ufb33\":0,\"\u00e9\":0,\"1\":0,\"\ud83d\ude00\":0,\"\\r\":0}"))
    }

    @Test fun `strings escape only what RFC 8785 requires`() {
        assertEquals("\"\\\"\\\\\\b\\f\\n\\r\\t\\u0001/\u20ac\"", canon("\"\\\"\\\\\\b\\f\\n\\r\\t\\u0001\\/\\u20ac\""))
    }

    @Test fun `integers only, within the safe range`() {
        assertEquals("[-9007199254740991,0,9007199254740991]", canon("[-9007199254740991,0,9007199254740991]"))
        assertThrows(CanonicalJsonException::class.java) { Jcs.parse("[1.5]") }
        assertThrows(CanonicalJsonException::class.java) { Jcs.parse("[1e3]") }
        assertThrows(CanonicalJsonException::class.java) { Jcs.parse("[9007199254740992]") }
        assertThrows(CanonicalJsonException::class.java) { Jcs.canonicalize(JsonPrimitive(2.5)) }
    }

    @Test fun `duplicate keys and trailing content are refused`() {
        assertThrows(CanonicalJsonException::class.java) { Jcs.parse("""{"a":1,"a":2}""") }
        assertThrows(CanonicalJsonException::class.java) { Jcs.parse("""{"a":1} x""") }
        assertThrows(CanonicalJsonException::class.java) { Jcs.parse("""{"a":1,}""") }
    }
}
