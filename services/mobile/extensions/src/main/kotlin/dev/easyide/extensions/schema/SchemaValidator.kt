package dev.easyide.extensions.schema

import dev.easyide.extensions.ExtensionPolicy
import dev.easyide.extensions.json.JsonPointer
import dev.easyide.extensions.json.intOrNull
import dev.easyide.extensions.json.jsonEquals
import dev.easyide.extensions.json.numberOrNull
import dev.easyide.extensions.json.schemaType
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.manifest.Diagnostic
import dev.easyide.extensions.manifest.DiagnosticCode
import dev.easyide.extensions.manifest.MANIFEST_FILE
import dev.easyide.extensions.manifest.Severity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Validates JSON against a fixed subset of JSON Schema draft 2020-12 ([SUPPORTED_KEYWORDS]).
 * No JSON Schema library is a dependency (extension-runtime.md sec 2.2); the subset is what
 * `manifest.schema.json` and contributed setting schemas need, and [unsupportedKeywords]
 * lets a test fail the build when the schema drifts outside it.
 *
 * Unknown-property findings use [unknownPropertySeverity]: manifests warn (R-API-08, so a
 * newer package still loads), setting values error.
 *
 * Instances are immutable and cheap: [forSubschema] shares the root (for `$ref`) and the
 * compiled pattern cache.
 */
class SchemaValidator private constructor(
    private val root: JsonObject,
    private val schema: JsonElement,
    private val file: String,
    private val unknownPropertySeverity: Severity,
    private val patterns: PatternCache,
) {
    /** Diagnostics for [value], whose location in [file] is [pointer]. */
    fun validate(value: JsonElement, pointer: String = ""): List<Diagnostic> {
        val out = ArrayList<Violation>()
        check(schema, value, pointer, out)
        return out.map {
            val code = when {
                it.keyword == KW_ADDITIONAL && it.severity == Severity.WARNING -> DiagnosticCode.UNKNOWN_KEY
                else -> DiagnosticCode.SCHEMA
            }
            Diagnostic(it.severity, code, it.pointer, file, it.message)
        }
    }

    /** A validator for [subschema] that resolves `$ref` against the same root. */
    fun forSubschema(subschema: JsonElement): SchemaValidator =
        SchemaValidator(root, subschema, file, unknownPropertySeverity, patterns)

    /** Resolves a local `#/$defs/<name>` reference; null for anything else. */
    fun definition(name: String): JsonElement? = (root[KW_DEFS] as? JsonObject)?.get(name)

    private data class Violation(val pointer: String, val keyword: String, val message: String, val severity: Severity) {
        val isError: Boolean get() = severity == Severity.ERROR
    }

    private fun check(schema: JsonElement, v: JsonElement, ptr: String, out: MutableList<Violation>) {
        if (schema is JsonPrimitive) {
            if (schema.content == "false") out += Violation(ptr, "false", "value not allowed here", Severity.ERROR)
            return
        }
        val s = schema as? JsonObject ?: return
        s[KW_REF]?.stringOrNull?.let { ref ->
            val target = resolveRef(ref)
            if (target == null) out += Violation(ptr, KW_REF, "unresolvable schema reference $ref", Severity.ERROR)
            else check(target, v, ptr, out)
        }
        s["type"]?.let { t ->
            val allowed = (t as? JsonArray)?.mapNotNull { it.stringOrNull } ?: listOfNotNull(t.stringOrNull)
            if (allowed.none { typeMatches(it, v) }) {
                out += Violation(ptr, "type", "expected ${allowed.joinToString(" or ")}, found ${v.schemaType}", Severity.ERROR)
                return  // every other keyword would only restate the type mismatch
            }
        }
        s["const"]?.let { c ->
            if (!jsonEquals(c, v)) out += Violation(ptr, "const", "must be $c", Severity.ERROR)
        }
        (s["enum"] as? JsonArray)?.let { e ->
            if (e.none { jsonEquals(it, v) }) out += Violation(ptr, "enum", "must be one of ${e.joinToString(", ")}", Severity.ERROR)
        }
        when (v) {
            is JsonObject -> checkObject(s, v, ptr, out)
            is JsonArray -> checkArray(s, v, ptr, out)
            is JsonPrimitive -> checkPrimitive(s, v, ptr, out)
        }
        (s["oneOf"] as? JsonArray)?.let { checkBranches(it, v, ptr, out, exactlyOne = true) }
        (s["anyOf"] as? JsonArray)?.let { checkBranches(it, v, ptr, out, exactlyOne = false) }
    }

    private fun checkPrimitive(s: JsonObject, v: JsonPrimitive, ptr: String, out: MutableList<Violation>) {
        val str = v.stringOrNull
        if (str != null) {
            val len = str.codePointCount(0, str.length)
            s["minLength"]?.intOrNull?.let { if (len < it) out += Violation(ptr, "minLength", "must be at least $it characters", Severity.ERROR) }
            s["maxLength"]?.intOrNull?.let { if (len > it) out += Violation(ptr, "maxLength", "must be at most $it characters", Severity.ERROR) }
            s["pattern"]?.stringOrNull?.let { p ->
                when (val compiled = patterns.get(p)) {
                    null -> out += Violation(ptr, "pattern", "schema pattern is invalid or longer than ${ExtensionPolicy.MAX_PATTERN_LENGTH}", Severity.ERROR)
                    else -> if (!compiled.containsMatchIn(str)) out += Violation(ptr, "pattern", "must match /$p/", Severity.ERROR)
                }
            }
            return
        }
        val n = v.numberOrNull ?: return
        s["minimum"]?.numberOrNull?.let { if (n < it) out += Violation(ptr, "minimum", "must be >= ${fmt(it)}", Severity.ERROR) }
        s["maximum"]?.numberOrNull?.let { if (n > it) out += Violation(ptr, "maximum", "must be <= ${fmt(it)}", Severity.ERROR) }
    }

    private fun checkArray(s: JsonObject, v: JsonArray, ptr: String, out: MutableList<Violation>) {
        s["minItems"]?.intOrNull?.let { if (v.size < it) out += Violation(ptr, "minItems", "must have at least $it items", Severity.ERROR) }
        s["maxItems"]?.intOrNull?.let { if (v.size > it) out += Violation(ptr, "maxItems", "must have at most $it items", Severity.ERROR) }
        val prefix = s["prefixItems"] as? JsonArray ?: JsonArray(emptyList())
        prefix.forEachIndexed { i, sub -> if (i < v.size) check(sub, v[i], JsonPointer.index(ptr, i), out) }
        val items = s["items"] ?: return
        for (i in prefix.size until v.size) check(items, v[i], JsonPointer.index(ptr, i), out)
    }

    private fun checkObject(s: JsonObject, v: JsonObject, ptr: String, out: MutableList<Violation>) {
        (s["required"] as? JsonArray)?.forEach { r ->
            val key = r.stringOrNull ?: return@forEach
            if (key !in v) out += Violation(JsonPointer.child(ptr, key), "required", "required property '$key' is missing", Severity.ERROR)
        }
        val props = s["properties"] as? JsonObject
        val patternProps = (s["patternProperties"] as? JsonObject)?.entries?.mapNotNull { (p, sub) -> patterns.get(p)?.let { it to sub } }
        val additional = s[KW_ADDITIONAL]
        for ((key, child) in v) {
            val childPtr = JsonPointer.child(ptr, key)
            var matched = false
            props?.get(key)?.let { matched = true; check(it, child, childPtr, out) }
            patternProps?.forEach { (re, sub) -> if (re.containsMatchIn(key)) { matched = true; check(sub, child, childPtr, out) } }
            if (matched || additional == null) continue
            if (additional is JsonPrimitive && additional.content == "false") {
                out += Violation(childPtr, KW_ADDITIONAL, "unknown property '$key'", unknownPropertySeverity)
            } else {
                check(additional, child, childPtr, out)
            }
        }
    }

    /**
     * For a failed `oneOf`/`anyOf`, report the errors of the branch the author evidently
     * meant, so the message points at the real mistake (`/easyide/actions/x/command`)
     * instead of "matches no alternative". A branch that fails a `const`/`enum` on a
     * direct child (a discriminator like `"type": "sandboxExec"`) was not meant.
     */
    private fun checkBranches(branches: JsonArray, v: JsonElement, ptr: String, out: MutableList<Violation>, exactlyOne: Boolean) {
        val results = branches.map { b -> ArrayList<Violation>().also { check(b, v, ptr, it) } }
        val passing = results.filter { r -> r.none { it.isError } }
        if (passing.size == 1 || (!exactlyOne && passing.isNotEmpty())) { out += passing.first(); return }
        if (passing.size > 1) {
            out += Violation(ptr, "oneOf", "matches more than one allowed form", Severity.ERROR)
            return
        }
        val meant = results.filter { r -> r.none { it.isError && isDiscriminatorMiss(it, ptr) } && r.none { it.isError && it.pointer == ptr && it.keyword == "type" } }
        val best = meant.minByOrNull { r -> r.count { it.isError } }
        if (best != null) out += best
        else out += Violation(ptr, if (exactlyOne) "oneOf" else "anyOf", "does not match any allowed form", Severity.ERROR)
    }

    private fun isDiscriminatorMiss(x: Violation, ptr: String): Boolean =
        (x.keyword == "const" || x.keyword == "enum") && x.pointer.startsWith("$ptr/") && x.pointer.indexOf('/', ptr.length + 1) < 0

    private fun resolveRef(ref: String): JsonElement? {
        if (!ref.startsWith(DEFS_PREFIX)) return null
        return definition(ref.removePrefix(DEFS_PREFIX))
    }

    private fun typeMatches(type: String, v: JsonElement): Boolean {
        val actual = v.schemaType
        return actual == type || (type == "number" && actual == "integer")
    }

    private fun fmt(d: Double): String = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

    /** Compiled `pattern`s; an invalid or over-long pattern maps to null and fails validation. */
    private class PatternCache {
        private val map = java.util.concurrent.ConcurrentHashMap<String, Result<Regex>>()

        fun get(p: String): Regex? = map.getOrPut(p) {
            if (p.length > ExtensionPolicy.MAX_PATTERN_LENGTH) Result.failure(IllegalArgumentException(p))
            // Author-supplied patterns (contributed setting schemas) are untrusted input.
            else runCatching { Regex(p) }
        }.getOrNull()
    }

    companion object {
        private const val KW_REF = "\$ref"
        private const val KW_DEFS = "\$defs"
        private const val KW_ADDITIONAL = "additionalProperties"
        private const val DEFS_PREFIX = "#/\$defs/"

        /** The draft 2020-12 subset this validator implements, plus annotation keywords. */
        val SUPPORTED_KEYWORDS: Set<String> = setOf(
            "type", "enum", "const", "required", "properties", "additionalProperties", "patternProperties",
            "items", "prefixItems", "minItems", "maxItems", "minimum", "maximum", "minLength", "maxLength",
            "pattern", "oneOf", "anyOf", KW_REF, KW_DEFS, "default", "description",
            // annotations only: no validation meaning
            "\$schema", "\$id", "\$comment", "title",
        )

        fun create(root: JsonObject, file: String = MANIFEST_FILE, unknownPropertySeverity: Severity = Severity.ERROR): SchemaValidator =
            SchemaValidator(root, root, file, unknownPropertySeverity, PatternCache())

        /**
         * JSON Pointers (into the schema) of keywords outside [SUPPORTED_KEYWORDS]. Property
         * names under `properties`, `patternProperties` and `$defs` are data, not keywords.
         */
        fun unsupportedKeywords(schema: JsonElement, pointer: String = ""): List<String> {
            val obj = schema as? JsonObject ?: return emptyList()
            val found = ArrayList<String>()
            for ((key, sub) in obj) {
                val p = JsonPointer.child(pointer, key)
                when (key) {
                    "properties", "patternProperties", KW_DEFS ->
                        (sub as? JsonObject)?.forEach { (name, s) -> found += unsupportedKeywords(s, JsonPointer.child(p, name)) }
                    "oneOf", "anyOf", "prefixItems" ->
                        (sub as? JsonArray)?.forEachIndexed { i, s -> found += unsupportedKeywords(s, JsonPointer.index(p, i)) }
                    "items", KW_ADDITIONAL -> found += unsupportedKeywords(sub, p)
                    "enum", "const", "default" -> Unit  // values, not schemas
                    else -> if (key !in SUPPORTED_KEYWORDS) found += p
                }
            }
            return found
        }
    }
}
