package dev.easyide.extensions.schema

import dev.easyide.extensions.ExtensionPolicy
import dev.easyide.extensions.manifest.DiagnosticCode
import dev.easyide.extensions.manifest.Severity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaValidatorTest {
    private fun schema(s: String) = SchemaValidator.create(Json.parseToJsonElement(s) as JsonObject)
    private fun errors(schema: String, value: String) =
        schema(schema).validate(Json.parseToJsonElement(value)).filter { it.severity == Severity.ERROR }
    private fun ok(schema: String, value: String) = assertEquals(emptyList<Any>(), errors(schema, value))
    private fun bad(schema: String, value: String, pointer: String = "") {
        val e = errors(schema, value)
        assertTrue("expected an error for $value", e.isNotEmpty())
        assertEquals(pointer, e.first().pointer)
    }

    @Test fun `type incl integer vs number and unions`() {
        ok("""{"type":"integer"}""", "3")
        ok("""{"type":"integer"}""", "3.0")
        bad("""{"type":"integer"}""", "3.5")
        ok("""{"type":"number"}""", "3")
        ok("""{"type":["string","null"]}""", "null")
        bad("""{"type":"string"}""", "1")
        bad("""{"type":"boolean"}""", "\"true\"")
        ok("""{"type":"array"}""", "[]")
        bad("""{"type":"object"}""", "[]")
    }

    @Test fun `enum and const compare structurally`() {
        ok("""{"enum":["a",1,{"x":[1]}]}""", """{"x":[1.0]}""")
        bad("""{"enum":["a","b"]}""", "\"c\"")
        ok("""{"const":"x"}""", "\"x\"")
        bad("""{"const":"x"}""", "\"y\"")
    }

    @Test fun `string length and pattern`() {
        ok("""{"minLength":2,"maxLength":3}""", "\"ab\"")
        bad("""{"minLength":2}""", "\"a\"")
        bad("""{"maxLength":1}""", "\"ab\"")
        ok("""{"maxLength":1}""", "\"\uD83D\uDE00\"")    // one code point
        ok("""{"pattern":"^a+$"}""", "\"aaa\"")
        bad("""{"pattern":"^a+$"}""", "\"ab\"")
        ok("""{"pattern":"b"}""", "\"abc\"")               // unanchored, as JSON Schema
    }

    @Test fun `invalid or over-long patterns fail instead of throwing`() {
        bad("""{"pattern":"("}""", "\"x\"")
        val long = "a".repeat(ExtensionPolicy.MAX_PATTERN_LENGTH + 1)
        bad("""{"pattern":"$long"}""", "\"a\"")
    }

    @Test fun `numeric bounds`() {
        ok("""{"minimum":1,"maximum":2}""", "1.5")
        bad("""{"minimum":1}""", "0")
        bad("""{"maximum":1}""", "2")
    }

    @Test fun `arrays with items, prefixItems and counts`() {
        ok("""{"items":{"type":"string"}}""", """["a","b"]""")
        bad("""{"items":{"type":"string"}}""", """["a",2]""", "/1")
        ok("""{"prefixItems":[{"type":"integer"}],"items":{"type":"string"}}""", """[1,"a"]""")
        bad("""{"prefixItems":[{"type":"integer"}],"items":{"type":"string"}}""", """["a","a"]""", "/0")
        bad("""{"minItems":1}""", "[]")
        bad("""{"maxItems":1}""", "[1,2]")
    }

    @Test fun `objects - required points at the missing child, properties recurse`() {
        bad("""{"required":["name"]}""", "{}", "/name")
        bad("""{"properties":{"a":{"properties":{"b":{"type":"string"}}}}}""", """{"a":{"b":1}}""", "/a/b")
        ok("""{"patternProperties":{"^x-":{"type":"integer"}}}""", """{"x-a":1}""")
        bad("""{"patternProperties":{"^x-":{"type":"integer"}}}""", """{"x-a":"s"}""", "/x-a")
        bad("""{"properties":{"a":{}},"additionalProperties":false}""", """{"b":1}""", "/b")
        bad("""{"additionalProperties":{"type":"string"}}""", """{"k":1}""", "/k")
    }

    @Test fun `pointer escaping`() {
        bad("""{"additionalProperties":{"type":"string"}}""", """{"a/b~c":1}""", "/a~1b~0c")
    }

    @Test fun `unknown properties can be warnings`() {
        val v = SchemaValidator.create(Json.parseToJsonElement("""{"properties":{"a":{}},"additionalProperties":false}""") as JsonObject,
            unknownPropertySeverity = Severity.WARNING)
        val d = v.validate(Json.parseToJsonElement("""{"b":1}"""))
        assertEquals(listOf(DiagnosticCode.UNKNOWN_KEY), d.map { it.code })
        assertEquals(Severity.WARNING, d.single().severity)
    }

    @Test fun `oneOf reports the branch the discriminator selects`() {
        val s = """{"oneOf":[
            {"properties":{"type":{"const":"a"},"x":{"type":"string"}},"required":["type","x"]},
            {"properties":{"type":{"const":"b"},"y":{"type":"integer"}},"required":["type","y"]}]}"""
        ok(s, """{"type":"b","y":1}""")
        bad(s, """{"type":"b","y":"no"}""", "/y")
        bad(s, """{"type":"a"}""", "/x")
        val none = errors(s, """{"type":"c"}""")
        assertEquals("", none.single().pointer)
    }

    @Test fun `oneOf rejects matching several branches, anyOf accepts`() {
        bad("""{"oneOf":[{"type":"integer"},{"type":"number"}]}""", "1")
        ok("""{"anyOf":[{"type":"integer"},{"type":"number"}]}""", "1")
        bad("""{"anyOf":[{"type":"integer"},{"type":"string"}]}""", "true")
    }

    @Test fun `local refs resolve, others fail`() {
        ok("""{"${'$'}defs":{"s":{"type":"string"}},"items":{"${'$'}ref":"#/${'$'}defs/s"}}""", """["a"]""")
        bad("""{"${'$'}defs":{"s":{"type":"string"}},"items":{"${'$'}ref":"#/${'$'}defs/s"}}""", "[1]", "/0")
        bad("""{"${'$'}ref":"http://example.com/x"}""", "1")
    }

    @Test fun `boolean schemas`() {
        val always = SchemaValidator.create(JsonObject(emptyMap())).forSubschema(JsonPrimitive(true))
        assertEquals(emptyList<Any>(), always.validate(JsonPrimitive(1)))
        val never = SchemaValidator.create(JsonObject(emptyMap())).forSubschema(JsonPrimitive(false))
        assertEquals(1, never.validate(JsonPrimitive(1)).size)
        bad("""{"properties":{"a":false}}""", """{"a":1}""", "/a")
    }

    @Test fun `unsupportedKeywords finds keywords outside the subset only`() {
        val s = Json.parseToJsonElement("""{"type":"object","properties":{"not":{"format":"uri"}},"if":{}}""")
        assertEquals(listOf("/properties/not/format", "/if"), SchemaValidator.unsupportedKeywords(s))
    }

    @Test fun `bundled manifest schema uses only the supported subset`() {
        assertEquals(emptyList<String>(), SchemaValidator.unsupportedKeywords(ManifestSchema.json))
    }

    @Test fun `every ref in the bundled schema resolves`() {
        val refs = Regex("\"\\${'$'}ref\"\\s*:\\s*\"#/\\${'$'}defs/([A-Za-z]+)\"").findAll(ManifestSchema.json.toString()).map { it.groupValues[1] }.toSet()
        val v = ManifestSchema.validator
        refs.forEach { assertTrue("unresolved $it", v.definition(it) != null) }
        assertTrue(refs.size > 20)
    }
}
