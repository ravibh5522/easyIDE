package dev.easyide.extensions.manifest

import dev.easyide.extensions.action.Action
import dev.easyide.extensions.action.OpenGroup
import dev.easyide.extensions.contrib.PackageFile
import dev.easyide.extensions.json.JsonParse
import dev.easyide.extensions.json.JsonPointer
import dev.easyide.extensions.json.JsonText
import dev.easyide.extensions.json.numberOrNull
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.schema.ManifestSchema
import dev.easyide.extensions.view.ActionTarget
import dev.easyide.extensions.view.Availability
import dev.easyide.extensions.view.Column
import dev.easyide.extensions.view.Confirm
import dev.easyide.extensions.view.Effect
import dev.easyide.extensions.view.EffectOp
import dev.easyide.extensions.view.Into
import dev.easyide.extensions.view.IntoMode
import dev.easyide.extensions.view.Option
import dev.easyide.extensions.view.PropKind
import dev.easyide.extensions.view.PropSpec
import dev.easyide.extensions.view.PropValue
import dev.easyide.extensions.view.ResultParse
import dev.easyide.extensions.view.Slots
import dev.easyide.extensions.view.ViewAction
import dev.easyide.extensions.view.ViewCatalog
import dev.easyide.extensions.view.ViewData
import dev.easyide.extensions.view.ViewDocument
import dev.easyide.extensions.view.ViewFormat
import dev.easyide.extensions.view.ViewLimits
import dev.easyide.extensions.view.ViewNode
import dev.easyide.extensions.view.ViewTemplate
import dev.easyide.extensions.view.ViewType
import dev.easyide.extensions.view.ViewValue
import dev.easyide.extensions.whenclause.WhenExpr
import dev.easyide.extensions.whenclause.WhenParseResult
import dev.easyide.extensions.whenclause.WhenParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

private data class Slotted(val children: List<ViewNode>, val item: ViewNode?, val empty: ViewNode?)

/**
 * Decodes `viewSchema: 1` files (extension-ui.md section 4) into [ViewDocument]s, checking the static
 * limits, the component catalog, templates, ids and action bindings. Problems are recorded against the
 * view file (not `package.json`), and a file with any error yields no document: the manifest is then
 * invalid, which is how `easyide-ext validate` and the install both refuse a broken view.
 * A file is decoded once, however many views or documents name it.
 */
internal class ViewSchemaDecoder(private val ctx: DecodeContext) {
    private val decoded = HashMap<String, ViewDocument?>()

    fun decode(file: PackageFile): ViewDocument? = decoded.getOrPut(file.path) { Run(file).document() }

    private inner class Run(private val file: PackageFile) {
        /** Findings of this file only; re-homed onto the file when the run ends. */
        private val scratch = DecodeContext(ctx.files, ctx.schema, ctx.extensionName, ctx.extensionId)
        private val ids = HashSet<String>()
        private var nodes = 0
        private var depth = 0
        private var composers = 0
        private var limitReported = false

        fun document(): ViewDocument? {
            val doc = read()?.let(::root)
            ctx.referenced += scratch.referenced
            scratch.diagnostics.forEach { ctx.diagnostics += it.copy(file = file.path) }
            return doc.takeIf { scratch.diagnostics.none { it.severity == Severity.ERROR } }
        }

        private fun read(): JsonObject? {
            if (ctx.files.size(file.path) > ViewLimits.MAX_FILE_BYTES) {
                scratch.error(DiagnosticCode.VIEW_LIMIT, "", "view file larger than ${ViewLimits.MAX_FILE_BYTES / 1024} KB")
                return null
            }
            val text = try { ctx.files.read(file.path).decodeToString() } catch (e: IOException) {
                scratch.error(DiagnosticCode.PACKAGE_IO, "", "cannot read: ${e.message}")
                return null
            }
            return when (val r = JsonText.parseStrict(text)) {
                is JsonParse.Error -> { scratch.error(DiagnosticCode.VIEW_SYNTAX, "", "line ${r.line}, column ${r.column}: ${r.message}"); null }
                is JsonParse.Ok -> (r.value as? JsonObject) ?: run { scratch.error(DiagnosticCode.VIEW_SYNTAX, "", "a view file is a JSON object"); null }
            }
        }

        private fun root(o: JsonObject): ViewDocument? {
            o.keys.filter { it !in ROOT_KEYS }.forEach { scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.child("", it), "unknown top-level key '$it'") }
            val version = o["viewSchema"]?.numberOrNull?.toInt()
            if (version != SCHEMA_VERSION) {
                scratch.error(DiagnosticCode.VIEW_VERSION, "/viewSchema", "this app reads viewSchema $SCHEMA_VERSION, the file says ${o["viewSchema"] ?: "nothing"}")
                return null
            }
            val state = when (val s = o["state"]) {
                null -> JsonObject(emptyMap())
                is JsonObject -> s
                else -> { scratch.error(DiagnosticCode.VIEW_PROP, "/state", "state must be an object"); JsonObject(emptyMap()) }
            }
            val rootObj = o["root"] as? JsonObject ?: run { scratch.error(DiagnosticCode.VIEW_PROP, "/root", "a view needs a root component"); return null }
            val node = node(rootObj, "/root", 1, null) ?: return null
            if (composers > 1) scratch.error(DiagnosticCode.VIEW_COMPOSER, "/root", "a view may hold one composer, found $composers")
            return ViewDocument(file.path, state, node, nodes, depth)
        }

        private fun limit(pointer: String, message: String) {
            if (limitReported) return
            limitReported = true
            scratch.error(DiagnosticCode.VIEW_LIMIT, pointer, message)
        }

        private fun node(o: JsonObject, p: String, level: Int, parent: ViewType?): ViewNode? {
            if (level > ViewLimits.MAX_DEPTH) { limit(p, "nesting deeper than ${ViewLimits.MAX_DEPTH}"); return null }
            if (++nodes > ViewLimits.MAX_NODES) { limit(p, "more than ${ViewLimits.MAX_NODES} components"); return null }
            depth = maxOf(depth, level)
            val typeName = o["type"]?.stringOrNull
            val type = typeName?.let(ViewType::parse)
            if (type == null) {
                scratch.error(DiagnosticCode.VIEW_COMPONENT, JsonPointer.child(p, "type"), "unknown component '${typeName ?: o["type"]}'")
                return null
            }
            val spec = type.spec
            if (spec.availability == Availability.RESERVED) {
                scratch.error(DiagnosticCode.VIEW_COMPONENT, JsonPointer.child(p, "type"), "'${type.wire}' is reserved and not available in viewSchema $SCHEMA_VERSION")
                return null
            }
            if ((type == ViewType.TAB) != (parent == ViewType.TABS)) {
                scratch.error(DiagnosticCode.VIEW_COMPONENT, JsonPointer.child(p, "type"), if (type == ViewType.TAB) "'tab' only goes inside 'tabs'" else "'tabs' holds only 'tab' components")
            }
            val known = (ViewCatalog.COMMON + spec.props).map { it.name }.toSet() + "type" + SLOT_KEYS +
                (if (spec.event != null) ViewCatalog.EVENT_PROPS else emptyList())
            o.keys.filter { it !in known }.forEach {
                scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.child(p, it), "'$it' is not a property of ${type.wire}")
            }
            val props = LinkedHashMap<String, PropValue>()
            (ViewCatalog.COMMON + spec.props).forEach { s -> prop(s, o, p)?.let { props[s.name] = it } }
            val id = (props["id"] as? PropValue.Path)?.path
            if (id != null && !ids.add(id)) scratch.error(DiagnosticCode.VIEW_ID, JsonPointer.child(p, "id"), "duplicate id '$id'")
            (props["weight"] as? PropValue.Num)?.takeIf { it.value <= 0 }?.let { scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.child(p, "weight"), "weight must be positive") }
            if (type == ViewType.COMPOSER) composers++
            if (type == ViewType.SELECT && (("options" in o) == ("optionsFrom" in o))) {
                scratch.error(DiagnosticCode.VIEW_PROP, p, "select needs exactly one of 'options' or 'optionsFrom'")
            }
            val (children, item, empty) = slots(spec.slots, type, o, p, level)
            val action = if (spec.event != null) binding(o, p, type, props) else null
            if (action == null && type in ACTION_REQUIRED) scratch.error(DiagnosticCode.VIEW_ACTION, p, "${type.wire} needs an 'action', an 'open' or 'before' effects")
            return ViewNode(type, id, (props["when"] as? PropValue.Condition)?.expr, props - "id" - "when", children, item, empty, action, p)
        }

        private fun slots(slots: Slots, type: ViewType, o: JsonObject, p: String, level: Int): Slotted {
            val hasChildren = slots == Slots.MANY || slots == Slots.PAIR || slots == Slots.TABS
            val hasItem = slots == Slots.ITEM || slots == Slots.ITEM_EMPTY
            val hasEmpty = slots == Slots.ITEM_EMPTY || slots == Slots.EMPTY
            if (!hasChildren && "children" in o) scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.child(p, "children"), "${type.wire} takes no children")
            if (!hasItem && "item" in o) scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.child(p, "item"), "${type.wire} takes no item template")
            if (!hasEmpty && "empty" in o) scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.child(p, "empty"), "${type.wire} takes no empty state")
            val children = if (hasChildren) children(o, p, level, type) else emptyList()
            if (slots == Slots.PAIR && children.size != 2) scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.child(p, "children"), "split needs exactly two children")
            if (slots == Slots.TABS && children.isEmpty()) scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.child(p, "children"), "tabs needs at least one tab")
            val item = if (hasItem) (o["item"] as? JsonObject)?.let { node(it, JsonPointer.child(p, "item"), level + 1, type) }
                ?: run { scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.child(p, "item"), "${type.wire} needs an item template"); null } else null
            val empty = if (hasEmpty) (o["empty"] as? JsonObject)?.let { node(it, JsonPointer.child(p, "empty"), level + 1, type) } else null
            return Slotted(children, item, empty)
        }

        private fun children(o: JsonObject, p: String, level: Int, parent: ViewType): List<ViewNode> {
            val cp = JsonPointer.child(p, "children")
            val arr = o["children"] as? JsonArray ?: return emptyList()
            return arr.mapIndexedNotNull { i, e ->
                val child = e as? JsonObject
                if (child == null) { scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.index(cp, i), "a child is a component object"); null }
                else node(child, JsonPointer.index(cp, i), level + 1, parent)
            }
        }

        // ---- props -------------------------------------------------------------------------------------------

        private fun prop(spec: PropSpec, o: JsonObject, p: String): PropValue? {
            val v = o[spec.name]
            val pp = JsonPointer.child(p, spec.name)
            if (v == null) {
                if (spec.required) scratch.error(DiagnosticCode.VIEW_PROP, pp, "'${spec.name}' is required")
                return null
            }
            return when (spec.kind) {
                PropKind.TEXT -> text(v, pp)?.let(PropValue::Text)
                PropKind.PATH -> path(v, pp)?.let(PropValue::Path)
                PropKind.FLAG -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()?.let(PropValue::Flag) ?: bad(pp, "must be true or false")
                PropKind.NUMBER -> v.numberOrNull?.let(PropValue::Num) ?: bad(pp, "must be a number")
                PropKind.CHOICE -> v.stringOrNull?.takeIf { it in spec.values }?.let(PropValue::Choice) ?: bad(pp, "must be one of ${spec.values.joinToString()}")
                PropKind.ICON -> v.stringOrNull?.let { scratch.uiIcon(it, pp) }?.let(PropValue::IconRef) ?: bad(pp, "must be an icon token or a pack SVG")
                PropKind.IMAGE -> image(v, pp)
                PropKind.WHEN -> condition(v, pp)?.let(PropValue::Condition)
                PropKind.PATHS -> paths(v, pp)
                PropKind.OPTIONS -> options(v, pp)
                PropKind.COLUMNS -> columns(v, pp)
                PropKind.PATTERN -> v.stringOrNull?.let { s -> scratch.regex(s, pp)?.let { PropValue.Pattern(s) } } ?: bad(pp, "must be a regular expression")
            }
        }

        /** Returns null after recording the problem, so a bad prop reads as absent and decoding goes on. */
        private fun bad(pointer: String, message: String): PropValue? {
            if (scratch.diagnostics.none { it.pointer == pointer && it.severity == Severity.ERROR }) scratch.error(DiagnosticCode.VIEW_PROP, pointer, message)
            return null
        }

        private fun text(v: JsonElement, pp: String): ViewTemplate? {
            val s = v.stringOrNull ?: run { scratch.error(DiagnosticCode.VIEW_PROP, pp, "must be a string"); return null }
            if (s.length > ViewLimits.MAX_STRING) { scratch.error(DiagnosticCode.VIEW_LIMIT, pp, "string longer than ${ViewLimits.MAX_STRING} characters"); return null }
            return when (val t = ViewTemplate.parse(s)) {
                is ViewTemplate.Parse.Ok -> t.template
                is ViewTemplate.Parse.Error -> { scratch.error(DiagnosticCode.VIEW_TEMPLATE, pp, "offset ${t.offset}: ${t.message}"); null }
            }
        }

        private fun path(v: JsonElement, pp: String): String? {
            val s = v.stringOrNull
            if (s == null || !PATH.matches(s)) { scratch.error(DiagnosticCode.VIEW_PROP, pp, "must be a data path such as 'containers' or 'session.title'"); return null }
            return s
        }

        private fun condition(v: JsonElement, pp: String): WhenExpr? {
            val s = v.stringOrNull ?: run { scratch.error(DiagnosticCode.VIEW_PROP, pp, "must be a when-clause string"); return null }
            // Keys of a view clause are the item's fields and the shell's context keys; only syntax is checkable here.
            return when (val r = WhenParser.parse(s)) {
                is WhenParseResult.Ok -> r.expr
                is WhenParseResult.Error -> { scratch.error(DiagnosticCode.WHEN_SYNTAX, pp, "column ${r.offset + 1}: ${r.message}"); null }
            }
        }

        private fun image(v: JsonElement, pp: String): PropValue? {
            val ref = v.stringOrNull ?: return bad(pp, "must be a package image path")
            if (IMAGE_EXTENSIONS.none { ref.lowercase().endsWith(it) }) return bad(pp, "images are ${IMAGE_EXTENSIONS.joinToString()} files in the package")
            val f = scratch.file(ref, pp) ?: return null
            if (ctx.files.size(f.path) > ViewLimits.IMAGE_MAX_BYTES) return bad(pp, "image larger than ${ViewLimits.IMAGE_MAX_BYTES / 1024} KB")
            return PropValue.Image(f)
        }

        private fun paths(v: JsonElement, pp: String): PropValue? {
            val arr = v as? JsonArray ?: return bad(pp, "must be an array of data paths")
            if (arr.size > ViewLimits.MAX_LITERAL_LIST) { scratch.error(DiagnosticCode.VIEW_LIMIT, pp, "more than ${ViewLimits.MAX_LITERAL_LIST} entries"); return null }
            return PropValue.Paths(arr.mapIndexedNotNull { i, e -> path(e, JsonPointer.index(pp, i)) })
        }

        private fun options(v: JsonElement, pp: String): PropValue? {
            val arr = v as? JsonArray ?: return bad(pp, "must be an array of options")
            if (arr.size > ViewLimits.MAX_LITERAL_LIST) { scratch.error(DiagnosticCode.VIEW_LIMIT, pp, "more than ${ViewLimits.MAX_LITERAL_LIST} options"); return null }
            return PropValue.Options(arr.mapIndexedNotNull { i, e ->
                val ip = JsonPointer.index(pp, i)
                when {
                    e is JsonPrimitive && e.isString -> text(e, ip)?.let { Option(it, e) }
                    e is JsonObject -> {
                        val label = e["label"]?.let { text(it, JsonPointer.child(ip, "label")) }
                        val value = e["value"]
                        if (label == null || value !is JsonPrimitive) bad(ip, "an option is a string or {label, value}").let { null } else Option(label, value)
                    }
                    else -> bad(ip, "an option is a string or {label, value}").let { null }
                }
            })
        }

        private fun columns(v: JsonElement, pp: String): PropValue? {
            val arr = v as? JsonArray ?: return bad(pp, "must be an array of columns")
            if (arr.isEmpty() || arr.size > ViewLimits.MAX_LITERAL_LIST) return bad(pp, "needs between 1 and ${ViewLimits.MAX_LITERAL_LIST} columns")
            return PropValue.Columns(arr.mapIndexedNotNull { i, e ->
                val cp = JsonPointer.index(pp, i)
                val o = e as? JsonObject ?: return@mapIndexedNotNull bad(cp, "a column is {title, field}").let { null }
                val title = o["title"]?.let { text(it, JsonPointer.child(cp, "title")) }
                val field = o["field"]?.let { path(it, JsonPointer.child(cp, "field")) }
                val format = o["format"]?.stringOrNull?.let { f -> ViewFormat.parse(f) ?: run { scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.child(cp, "format"), "unknown format '$f'"); null } }
                if (title == null || field == null) null
                else Column(title, field, format, o["mono"]?.let { it is JsonPrimitive && it.content == "true" } == true, o["weight"]?.numberOrNull ?: 1.0)
            })
        }

        // ---- events ------------------------------------------------------------------------------------------

        private fun binding(o: JsonObject, p: String, type: ViewType, props: Map<String, PropValue>): ViewAction? {
            val action = o["action"]
            val open = o["open"]
            val before = effects(o["before"], JsonPointer.child(p, "before"))
            val after = effects(o["after"], JsonPointer.child(p, "after"))
            if (action != null && open != null) scratch.error(DiagnosticCode.VIEW_ACTION, p, "give 'action' or 'open', not both")
            val target: ActionTarget? = when {
                action != null -> target(action, JsonPointer.child(p, "action"))
                open != null -> openTarget(open, o["group"], p)
                before.isNotEmpty() || after.isNotEmpty() -> ActionTarget.Local
                else -> null
            }
            if (target == null) return null
            if ("group" in o && open == null) scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.child(p, "group"), "'group' goes with 'open'")
            val args = o["args"]?.let { ViewValue.of(it, { rel, e -> scratch.error(DiagnosticCode.VIEW_TEMPLATE, JsonPointer.child(p, "args") + rel, "offset ${e.offset}: ${e.message}") }) }
            val into = into(o, p, target)
            val confirm = o["confirm"]?.let { confirm(it, JsonPointer.child(p, "confirm")) }
            val danger = (props["style"] as? PropValue.Choice)?.value == "danger" || (props["tone"] as? PropValue.Choice)?.value == "danger"
            if (danger && confirm == null && type in ACTION_REQUIRED) {
                scratch.error(DiagnosticCode.VIEW_CONFIRM, p, "a destructive ${type.wire} must carry 'confirm' naming the object and its consequences")
            }
            return ViewAction(target, args, confirm, into, before, after)
        }

        private fun target(v: JsonElement, pp: String): ActionTarget? = when {
            v is JsonPrimitive && v.isString -> ActionTarget.Command(v.content)
            v is JsonObject -> inline(v, pp)?.let(ActionTarget::Inline)
            else -> { scratch.error(DiagnosticCode.VIEW_ACTION, pp, "an action is a command id or an inline action object"); null }
        }

        /** An inline action is checked by the manifest's own `action` schema, then decoded like `easyide.actions`. */
        private fun inline(o: JsonObject, pp: String): Action? {
            val validator = ManifestSchema.validator
            val problems = validator.forSubschema(validator.definition("action")!!).validate(o, pp)
            problems.forEach { scratch.diagnostics += it }
            if (problems.any { it.severity == Severity.ERROR }) return null
            return ActionDecoder(scratch).action(o, pp)
        }

        private fun openTarget(open: JsonElement, group: JsonElement?, p: String): ActionTarget? {
            val pp = JsonPointer.child(p, "open")
            val t = text(open, pp) ?: return null
            val prefix = (t.parts.firstOrNull() as? ViewTemplate.Part.Literal)?.text.orEmpty()
            if (!prefix.startsWith("ext://${ctx.extensionId.value}/")) {
                scratch.error(DiagnosticCode.VIEW_ACTION, pp, "'open' must start with 'ext://${ctx.extensionId.value}/': a pack opens its own documents")
                return null
            }
            val g = group?.stringOrNull?.let(OpenGroup::parse)
            if (group != null && g == null) scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.child(p, "group"), "must be active, beside or new")
            return ActionTarget.Open(t, g ?: OpenGroup.ACTIVE)
        }

        private fun into(o: JsonObject, p: String, target: ActionTarget): Into? {
            // "." merges an object result into the data by top-level key, as a provider's update does.
            val path = o["as"]?.let { if (it.stringOrNull == ViewData.MERGE) ViewData.MERGE else path(it, JsonPointer.child(p, "as")) }
            if (path == null) {
                listOf("mode", "parse").filter { it in o }.forEach { scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.child(p, it), "'$it' goes with 'as'") }
                return null
            }
            if (target !is ActionTarget.Command && target !is ActionTarget.Inline) {
                scratch.error(DiagnosticCode.VIEW_ACTION, JsonPointer.child(p, "as"), "only a command or inline action has a result to write back")
                return null
            }
            val mode = o["mode"]?.stringOrNull?.let { m -> IntoMode.entries.firstOrNull { it.wire == m } }
            val parse = o["parse"]?.stringOrNull?.let { m -> ResultParse.entries.firstOrNull { it.wire == m } }
            if ("mode" in o && mode == null) scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.child(p, "mode"), "must be set or append")
            if ("parse" in o && parse == null) scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.child(p, "parse"), "must be json, text or lines")
            return Into(path, mode ?: IntoMode.SET, parse ?: ResultParse.JSON)
        }

        private fun confirm(v: JsonElement, pp: String): Confirm? {
            val o = v as? JsonObject ?: run { scratch.error(DiagnosticCode.VIEW_PROP, pp, "confirm is {title, body?, destructive?}"); return null }
            val title = o["title"]?.let { text(it, JsonPointer.child(pp, "title")) } ?: run { scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.child(pp, "title"), "confirm needs a title"); return null }
            val destructive = o["destructive"]?.let { it is JsonPrimitive && it.content == "true" } == true
            return Confirm(title, o["body"]?.let { text(it, JsonPointer.child(pp, "body")) }, destructive)
        }

        private fun effects(v: JsonElement?, pp: String): List<Effect> {
            val arr = v as? JsonArray ?: return emptyList<Effect>().also { if (v != null) scratch.error(DiagnosticCode.VIEW_PROP, pp, "effects are an array of {op, path, value?}") }
            if (arr.size > ViewLimits.MAX_LITERAL_LIST) { scratch.error(DiagnosticCode.VIEW_LIMIT, pp, "more than ${ViewLimits.MAX_LITERAL_LIST} effects"); return emptyList() }
            return arr.mapIndexedNotNull { i, e ->
                val ep = JsonPointer.index(pp, i)
                val o = e as? JsonObject ?: return@mapIndexedNotNull null.also { scratch.error(DiagnosticCode.VIEW_PROP, ep, "an effect is {op, path, value?}") }
                val op = o["op"]?.stringOrNull?.let { w -> EffectOp.entries.firstOrNull { it.wire == w } }
                val path = o["path"]?.let { path(it, JsonPointer.child(ep, "path")) }
                if (op == null) scratch.error(DiagnosticCode.VIEW_PROP, JsonPointer.child(ep, "op"), "must be set, append or clear")
                if (op != null && op != EffectOp.CLEAR && "value" !in o) scratch.error(DiagnosticCode.VIEW_PROP, ep, "${op.wire} needs a value")
                val value = o["value"]?.let { ViewValue.of(it, { rel, err -> scratch.error(DiagnosticCode.VIEW_TEMPLATE, JsonPointer.child(ep, "value") + rel, "offset ${err.offset}: ${err.message}") }) }
                if (op == null || path == null) null else Effect(op, path, value)
            }
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private val ROOT_KEYS = setOf("\$schema", "viewSchema", "state", "root")
        private val SLOT_KEYS = setOf("children", "item", "empty")
        private val PATH = Regex("""[A-Za-z_][A-Za-z0-9_-]*(\.[A-Za-z0-9_-]+)*""")
        private val IMAGE_EXTENSIONS = listOf(".png", ".webp", ".jpg", ".jpeg")
        private val ACTION_REQUIRED = setOf(ViewType.BUTTON, ViewType.ICON_BUTTON, ViewType.COMPOSER)
    }
}
