package dev.easyide.extensions.manifest

import dev.easyide.extensions.contrib.BadgeBinding
import dev.easyide.extensions.contrib.BadgeKind
import dev.easyide.extensions.contrib.ContainerPlacement
import dev.easyide.extensions.contrib.DocumentContribution
import dev.easyide.extensions.contrib.DocumentOpenerContribution
import dev.easyide.extensions.contrib.DocumentStateProvider
import dev.easyide.extensions.contrib.LayoutPresetContribution
import dev.easyide.extensions.contrib.NavigationContribution
import dev.easyide.extensions.contrib.NavigationTarget
import dev.easyide.extensions.contrib.OpenerPriorityName
import dev.easyide.extensions.contrib.SizeClassName
import dev.easyide.extensions.contrib.StageSplit
import dev.easyide.extensions.contrib.UiScope
import dev.easyide.extensions.contrib.ViewBadgeContribution
import dev.easyide.extensions.contrib.ViewContainerContribution
import dev.easyide.extensions.json.JsonPointer
import dev.easyide.extensions.json.booleanOrNull
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.view.ViewLimits
import dev.easyide.extensions.view.ViewTemplate
import kotlinx.serialization.json.JsonObject

/**
 * Decodes the shell-facing points under `easyide`: `navigation`, `viewBadge`, `documents`, `documentOpeners` and
 * `layoutPresets` (extension-ui.md section 2). Ids must be `<publisher>.<name>.<part>` because the shell refuses any
 * other spelling; counts and lengths are the [ViewLimits]. Cross-references (a nav target naming a container, an
 * opener naming a type) are checked afterwards in [ManifestChecks], where every point is known.
 */
internal class UiDecoder(private val ctx: DecodeContext, private val views: ViewSchemaDecoder) {
    private val base = "/easyide"

    fun navigation(e: JsonObject, containers: List<ViewContainerContribution>): List<NavigationContribution> {
        val raw = e.objs("navigation")
        val all = raw.mapIndexedNotNull { i, o -> navItem(o, JsonPointer.index("$base/navigation", i), containers) }
        ctx.unique(all.mapIndexed { i, n -> JsonPointer.index("$base/navigation", i) to n.id }, "navigation item")
        if (all.size <= ViewLimits.MAX_NAV_ITEMS_PER_PACK) return all
        ctx.warn(DiagnosticCode.UI_IGNORED, "$base/navigation", "only the first ${ViewLimits.MAX_NAV_ITEMS_PER_PACK} navigation items are used; ${all.size - ViewLimits.MAX_NAV_ITEMS_PER_PACK} ignored")
        return all.take(ViewLimits.MAX_NAV_ITEMS_PER_PACK)
    }

    private fun navItem(o: JsonObject, p: String, containers: List<ViewContainerContribution>): NavigationContribution? {
        val id = o.reqStr("id")
        var ok = ctx.uiPrefix(id, JsonPointer.child(p, "id"), "navigation item")
        val title = o.reqStr("title")
        if (title.length > ViewLimits.NAV_TITLE_MAX) {
            ctx.error(DiagnosticCode.UI_TITLE, JsonPointer.child(p, "title"), "'$title' is longer than ${ViewLimits.NAV_TITLE_MAX} characters (it is a label under a 24dp icon)")
            ok = false
        }
        val icon = ctx.uiIcon(o.reqStr("icon"), JsonPointer.child(p, "icon")) ?: return null
        val target = o.obj("target")!!.let { t ->
            val container = t.str("container")
            val command = t.str("command")
            if ((container == null) == (command == null)) {
                ctx.error(DiagnosticCode.ONE_OF, JsonPointer.child(p, "target"), "a target needs exactly one of 'container' or 'command'")
                return null
            }
            if (container != null) NavigationTarget.Container(container) else NavigationTarget.Command(command!!)
        }
        val order = o.int("order") ?: DEFAULT_ORDER
        if (order < ViewLimits.EXTENSION_ORDER_FLOOR) {
            ctx.warn(DiagnosticCode.UI_ORDER, JsonPointer.child(p, "order"), "order $order is below ${ViewLimits.EXTENSION_ORDER_FLOOR}, which is reserved for built-ins; the shell raises it")
        }
        val scope = o.str("scope")?.let(UiScope::parse)
            ?: (target as? NavigationTarget.Container)?.let { t -> containers.firstOrNull { it.id == t.id }?.scope }
            ?: UiScope.WORKSPACE
        val badge = o.obj("badge")?.let { badge(it, JsonPointer.child(p, "badge")) }
        return NavigationContribution(id, title, icon, target, scope, order, ctx.whenAt(o, "when", p), badge).takeIf { ok }
    }

    private fun badge(o: JsonObject, p: String) = BadgeBinding(o.reqStr("view"), o.reqStr("path"), o.str("kind")?.let(BadgeKind::parse) ?: BadgeKind.COUNT)

    fun viewBadges(e: JsonObject): List<ViewBadgeContribution> = e.objs("viewBadge").mapIndexed { i, o ->
        ViewBadgeContribution(o.reqStr("nav"), badge(o, JsonPointer.index("$base/viewBadge", i)))
    }

    fun documents(e: JsonObject): List<DocumentContribution> {
        val raw = e.objs("documents")
        if (raw.size > ViewLimits.MAX_DOCUMENTS_PER_PACK) {
            ctx.error(DiagnosticCode.UI_LIMIT, "$base/documents", "${raw.size} document types; at most ${ViewLimits.MAX_DOCUMENTS_PER_PACK} per pack")
        }
        val out = raw.mapIndexedNotNull { i, o -> document(o, JsonPointer.index("$base/documents", i)) }
        ctx.unique(out.mapIndexed { i, d -> JsonPointer.index("$base/documents", i) to d.type }, "document type")
        return out
    }

    private fun document(o: JsonObject, p: String): DocumentContribution? {
        val type = o.reqStr("type")
        val typePointer = JsonPointer.child(p, "type")
        val name = type.substringAfter('/', "")
        val prefixOk = ctx.uiPrefix(type, typePointer, "document type", '/')
        if (prefixOk && !TYPE_NAME.matches(name)) {
            ctx.error(DiagnosticCode.UI_ID, typePointer, "'$type' must end in a lower-case name after '/'")
        }
        val title = when (val t = ViewTemplate.parse(o.reqStr("title"))) {
            is ViewTemplate.Parse.Ok -> t.template
            is ViewTemplate.Parse.Error -> { ctx.error(DiagnosticCode.VIEW_TEMPLATE, JsonPointer.child(p, "title"), "offset ${t.offset}: ${t.message}"); return null }
        }
        val icon = ctx.uiIcon(o.reqStr("icon"), JsonPointer.child(p, "icon")) ?: return null
        val file = ctx.fileAt(o, "schema", p) ?: return null
        val body = views.decode(file) ?: return null
        val state = o.obj("state")?.let { DocumentStateProvider(it.reqStr("provider"), it.int("intervalSec")) }
        return DocumentContribution(type, title, icon, body, o.bool("multiple") ?: false, o.bool("supportsSplit") ?: true, state)
    }

    fun documentOpeners(e: JsonObject): List<DocumentOpenerContribution> {
        val raw = e.objs("documentOpeners")
        if (raw.size > ViewLimits.MAX_OPENERS_PER_PACK) {
            ctx.error(DiagnosticCode.UI_LIMIT, "$base/documentOpeners", "${raw.size} openers; at most ${ViewLimits.MAX_OPENERS_PER_PACK} per pack")
        }
        return raw.mapIndexedNotNull { i, o ->
            val p = JsonPointer.index("$base/documentOpeners", i)
            val wire = o.str("priority") ?: OpenerPriorityName.OPTION.wire
            val priority = OpenerPriorityName.parse(wire)
            if (priority == null) {
                val why = if (wire == "builtin") "'builtin' is reserved for core" else "must be default or option"
                ctx.error(DiagnosticCode.UI_PRIORITY, JsonPointer.child(p, "priority"), why)
                return@mapIndexedNotNull null
            }
            DocumentOpenerContribution(o.reqStr("glob"), o.reqStr("type"), priority)
        }
    }

    fun layoutPresets(e: JsonObject): List<LayoutPresetContribution> {
        val raw = e.objs("layoutPresets")
        if (raw.size > ViewLimits.MAX_PRESETS_PER_PACK) {
            ctx.error(DiagnosticCode.UI_LIMIT, "$base/layoutPresets", "${raw.size} layout presets; at most ${ViewLimits.MAX_PRESETS_PER_PACK} per pack")
        }
        val out = raw.mapIndexedNotNull { i, o -> preset(o, JsonPointer.index("$base/layoutPresets", i)) }
        ctx.unique(out.mapIndexed { i, x -> JsonPointer.index("$base/layoutPresets", i) to x.id }, "layout preset")
        return out
    }

    private fun preset(o: JsonObject, p: String): LayoutPresetContribution? {
        val id = o.reqStr("id")
        var ok = ctx.uiPrefix(id, JsonPointer.child(p, "id"), "layout preset")
        val title = o.reqStr("title")
        if (title.length > ViewLimits.TITLE_MAX) {
            ctx.error(DiagnosticCode.UI_TITLE, JsonPointer.child(p, "title"), "longer than ${ViewLimits.TITLE_MAX} characters")
            ok = false
        }
        val sizes = o.strs("sizeClass").mapNotNull(SizeClassName::parse).toSet()
        val containers = o.obj("containers")?.mapNotNull { (k, v) ->
            ContainerPlacement.parse(k)?.let { placement -> v.stringOrNull?.let { placement to it } }
        }?.toMap().orEmpty()
        val panels = o.obj("panels")?.mapNotNull { (k, v) ->
            ContainerPlacement.parse(k)?.let { placement -> v.booleanOrNull?.let { placement to it } }
        }?.toMap().orEmpty()
        val stage = o.obj("stage")?.let { StageSplit(it.str("split") ?: "row", it.int("groups") ?: 1) }
        return LayoutPresetContribution(id, title, sizes, containers, panels, stage).takeIf { ok }
    }

    companion object {
        const val DEFAULT_ORDER = 300
        private val TYPE_NAME = Regex("[a-z0-9][a-z0-9-]{0,62}")
    }
}
