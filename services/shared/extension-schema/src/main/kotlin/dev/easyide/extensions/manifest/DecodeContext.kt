package dev.easyide.extensions.manifest

import dev.easyide.extensions.ExtensionPolicy
import dev.easyide.extensions.action.Template
import dev.easyide.extensions.contrib.CommandIcon
import dev.easyide.extensions.contrib.PackageFile
import dev.easyide.extensions.json.JsonPointer
import dev.easyide.extensions.json.booleanOrNull
import dev.easyide.extensions.json.intOrNull
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.schema.SchemaValidator
import dev.easyide.extensions.view.SvgIconRules
import dev.easyide.extensions.whenclause.ContextKeys
import dev.easyide.extensions.whenclause.WhenExpr
import dev.easyide.extensions.whenclause.WhenParseResult
import dev.easyide.extensions.whenclause.WhenParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * Shared state of one manifest decode: the package, the diagnostics sink, and the set of
 * files the manifest references (phase 6 warns about the rest). Decoding runs after schema
 * validation, so shapes are known; what is checked here is what a schema cannot express
 * (templates, when-clauses, regexes, file existence).
 */
internal class DecodeContext(
    val files: PackageFiles,
    val schema: SchemaValidator,
    val extensionName: String,
    val extensionId: ExtensionId,
) {
    val diagnostics = ArrayList<Diagnostic>()
    val referenced = HashSet<String>()

    fun error(code: String, pointer: String, message: String, file: String = MANIFEST_FILE) { diagnostics += Diagnostic.error(code, pointer, message, file) }
    fun warn(code: String, pointer: String, message: String, file: String = MANIFEST_FILE) { diagnostics += Diagnostic.warning(code, pointer, message, file) }

    /** Parses a when-clause; a syntax error is E_WHEN_SYNTAX, an unknown key a warning. */
    fun whenExpr(text: String?, pointer: String): WhenExpr? {
        if (text == null) return null
        return when (val r = WhenParser.parse(text)) {
            is WhenParseResult.Error -> { error(DiagnosticCode.WHEN_SYNTAX, pointer, "column ${r.offset + 1}: ${r.message}"); null }
            is WhenParseResult.Ok -> r.expr.also { e ->
                e.keys.filterNot(ContextKeys::isKnown).sorted().forEach {
                    warn(DiagnosticCode.WHEN_UNKNOWN_KEY, pointer, "unknown context key '$it' (always undefined)")
                }
            }
        }
    }

    fun whenAt(o: JsonObject, key: String, pointer: String): WhenExpr? = whenExpr(o.str(key), JsonPointer.child(pointer, key))

    fun template(text: String, pointer: String): Template = when (val p = Template.parse(text)) {
        is Template.Parse.Ok -> p.template
        is Template.Parse.Error -> { error(DiagnosticCode.TEMPLATE, pointer, "offset ${p.offset}: ${p.message}"); Template.literal(text) }
    }

    fun templateAt(o: JsonObject, key: String, pointer: String): Template? = o.str(key)?.let { template(it, JsonPointer.child(pointer, key)) }

    /** A package file reference: normalised, present, and recorded as referenced. */
    fun file(ref: String, pointer: String): PackageFile? {
        val path = PackagePaths.normalize(ref)
        if (path == null) {
            error(DiagnosticCode.PATH_INVALID, pointer, "'$ref' must be a relative path inside the package")
            return null
        }
        if (!files.exists(path)) {
            error(DiagnosticCode.PATH_MISSING, pointer, "'$path' does not exist in the package")
            return null
        }
        referenced += path
        return PackageFile(path, files.hostPath(path))
    }

    fun fileAt(o: JsonObject, key: String, pointer: String): PackageFile? = o.str(key)?.let { file(it, JsonPointer.child(pointer, key)) }

    /** Icon tokens are bare names; anything with a `/` or `.svg` is a package SVG (R-EXT-11: no raster icons). */
    fun icon(value: String, pointer: String): CommandIcon? {
        if (!value.contains('/') && !value.contains('.')) return CommandIcon.Token(value)
        if (!value.lowercase().endsWith(".svg")) {
            error(DiagnosticCode.PATH_INVALID, pointer, "icons must be an easyIDE icon token or an SVG file")
            return null
        }
        return file(value, pointer)?.let(CommandIcon::Svg)
    }

    /**
     * An icon of a UI contribution point (navigation, containers, documents, view components): a token from the
     * icon set, or a pack SVG that must be a single-colour vector on the 24 grid (extension-ui.md sections 2.1 and 9.3).
     * Older points keep [icon]'s looser rule so existing packs behave as before.
     */
    fun uiIcon(value: String, pointer: String, file: String = MANIFEST_FILE): CommandIcon? {
        val icon = icon(value, pointer) ?: return null
        val svg = (icon as? CommandIcon.Svg)?.file ?: return icon
        val bytes = try { files.read(svg.path) } catch (e: IOException) {
            diagnostics += Diagnostic.error(DiagnosticCode.PATH_INVALID, pointer, "cannot read '${svg.path}': ${e.message}", file)
            return null
        }
        val problems = SvgIconRules.check(bytes.decodeToString(), bytes.size)
        problems.forEach { diagnostics += Diagnostic.error(DiagnosticCode.ICON_RULE, pointer, "icon '${svg.path}': $it", file) }
        return icon.takeIf { problems.isEmpty() }
    }

    /** Ids of UI points are `<publisher>.<name>.<part>`: the shell refuses any other spelling (Origin.owns), so it is an error here. */
    fun uiPrefix(id: String, pointer: String, what: String, separator: Char = '.'): Boolean {
        val ok = id.length > extensionId.value.length + 1 && id.startsWith(extensionId.value) && id[extensionId.value.length] == separator
        if (!ok) error(DiagnosticCode.UI_ID, pointer, "$what '$id' must start with '${extensionId.value}$separator'")
        return ok
    }

    /** A regex authors supply (`firstLine`, input `validate`); bounded like every other pattern. */
    fun regex(text: String, pointer: String): Regex? {
        if (text.length > ExtensionPolicy.MAX_PATTERN_LENGTH) {
            error(DiagnosticCode.REGEX, pointer, "pattern longer than ${ExtensionPolicy.MAX_PATTERN_LENGTH} characters")
            return null
        }
        // Author text: an invalid pattern is reported, not thrown.
        return try { Regex(text) } catch (e: IllegalArgumentException) {
            error(DiagnosticCode.REGEX, pointer, "invalid regex: ${e.message?.lineSequence()?.first()}")
            null
        }
    }

    /**
     * `env` maps: keys become `K=value` shell assignments in `runInTerminal`, so a key that is
     * not a POSIX variable name could inject shell syntax; refuse it at load.
     */
    fun envMap(o: JsonObject?, pointer: String): Map<String, Template> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, Template>()
        for ((k, v) in o) {
            val p = JsonPointer.child(pointer, k)
            if (!ENV_KEY.matches(k)) { error(DiagnosticCode.ENV_NAME, p, "'$k' is not a valid environment variable name"); continue }
            out[k] = template(v.stringOrNull.orEmpty(), p)
        }
        return out
    }

    /** Whether [id] is in this extension's namespace: `<name>.` (sdk-reference) or the full `<publisher>.<name>.` the shell points require. */
    fun ownsId(id: String): Boolean = id.startsWith("$extensionName.") || id.startsWith("${extensionId.value}.")

    /** Warns when an id is not namespaced `<name>.` or `<publisher>.<name>.` (sdk-reference validate rule). */
    fun checkPrefix(id: String, pointer: String, what: String) {
        if (!ownsId(id)) warn(DiagnosticCode.ID_PREFIX, pointer, "$what '$id' should start with '$extensionName.'")
    }

    /** Reports duplicates in [ids] (pointer, id) as E_DUPLICATE_ID at the later occurrence. */
    fun unique(ids: List<Pair<String, String>>, what: String) {
        val seen = HashSet<String>()
        for ((pointer, id) in ids) if (!seen.add(id)) error(DiagnosticCode.DUPLICATE_ID, pointer, "duplicate $what '$id'")
    }

    companion object {
        private val ENV_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

// Typed reads over schema-validated JSON. Required fields use `req*`: the schema guarantees
// them, so a miss is a schema/decoder drift bug caught by the fixture tests.
internal fun JsonObject.str(key: String): String? = this[key]?.stringOrNull
internal fun JsonObject.reqStr(key: String): String = str(key) ?: error("schema guarantees '$key'")
internal fun JsonObject.bool(key: String): Boolean? = this[key]?.booleanOrNull
internal fun JsonObject.int(key: String): Int? = this[key]?.intOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.strs(key: String): List<String> = arr(key)?.mapNotNull { it.stringOrNull } ?: emptyList()
internal fun JsonObject.objs(key: String): List<JsonObject> = arr(key)?.mapNotNull { it as? JsonObject } ?: emptyList()

/** VS Code accepts a single object where an array is expected (`commands`, `keybindings`). */
internal fun JsonElement?.objectList(): List<JsonObject> = when (this) {
    is JsonObject -> listOf(this)
    is JsonArray -> mapNotNull { it as? JsonObject }
    else -> emptyList()
}

/** Pointer of element [i] when the value was an array, or the value itself when it was one object. */
internal fun listPointer(value: JsonElement?, base: String, i: Int): String = if (value is JsonArray) JsonPointer.index(base, i) else base
