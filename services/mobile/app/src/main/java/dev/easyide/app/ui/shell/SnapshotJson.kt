package dev.easyide.app.ui.shell

import dev.easyide.app.ui.screens.workspace.layout.PaneArrangement
import dev.easyide.app.ui.screens.workspace.layout.PaneSizes
import dev.easyide.app.ui.screens.workspace.layout.StageVisibility
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * The JSON shape of layouts and stages inside a [ShellSnapshot]. Built and read with the tree API
 * only (decision 0013: no serialization plugin). Reading never throws: a value of the wrong type is
 * treated as absent, so a damaged or newer file degrades instead of failing a restore.
 */
internal object SnapshotJson {
    /** The stable spelling of each arrangement in saved files; independent of the enum's names. */
    private val ARRANGEMENTS = mapOf(
        PaneArrangement.SINGLE_PANE to "compact",
        PaneArrangement.ONE_SIDE to "medium",
        PaneArrangement.FULL to "expanded",
        PaneArrangement.BOOK to "book",
        PaneArrangement.TABLETOP to "tabletop",
    )

    private val TAB_STATES = TabState.entries.associateBy { it.name.lowercase() }

    fun arrangementFrom(wire: String): PaneArrangement? = ARRANGEMENTS.entries.firstOrNull { it.value == wire }?.key

    fun layouts(current: PaneArrangement, scope: ScopeState): JsonObject {
        val all = scope.saved + (current to scope.layout)
        return buildJsonObject {
            PaneArrangement.entries.filter { it in all }.forEach { put(ARRANGEMENTS.getValue(it), layout(all.getValue(it))) }
        }
    }

    private fun layout(l: PanelLayout): JsonObject = buildJsonObject {
        put("preset", l.preset)
        put("containers", buildJsonObject {
            Placement.entries.forEach { p -> l.containers[p]?.let { put(p.wire, it) } }
        })
        put("open", buildJsonArray { Placement.entries.filter(l::isOpen).forEach { add(JsonPrimitive(it.wire)) } })
        put("sizes", buildJsonObject {
            l.sizes.explorer?.let { put(Placement.SIDEBAR.wire, it) }
            l.sizes.right?.let { put(Placement.SECONDARY_SIDEBAR.wire, it) }
            l.sizes.bottom?.let { put(Placement.PANEL.wire, it) }
        })
    }

    fun layoutsFrom(element: JsonElement?): Map<PaneArrangement, PanelLayout> {
        val out = LinkedHashMap<PaneArrangement, PanelLayout>()
        (element as? JsonObject)?.forEach { (wire, value) ->
            val arrangement = arrangementFrom(wire)
            if (arrangement != null && value is JsonObject) out[arrangement] = layoutFrom(value)
        }
        return out
    }

    private fun layoutFrom(o: JsonObject): PanelLayout {
        val containers = (o["containers"] as? JsonObject).orEmpty().mapNotNull { (wire, v) ->
            val placement = Placement.ofWire(wire)
            val id = text(v)
            if (placement != null && id != null) placement to id else null
        }.toMap()
        val open = (o["open"] as? JsonArray).orEmpty().mapNotNull { text(it)?.let(Placement::ofWire) }.toSet()
        val sizes = o["sizes"] as? JsonObject
        fun size(p: Placement) = (sizes?.get(p.wire) as? JsonPrimitive)?.doubleOrNull?.toFloat()
        return PanelLayout(
            preset = text(o["preset"]) ?: LayoutPresets.AUTO,
            containers = containers,
            open = StageVisibility(
                left = Placement.SIDEBAR in open,
                right = Placement.SECONDARY_SIDEBAR in open,
                bottom = Placement.PANEL in open,
            ),
            sizes = PaneSizes(size(Placement.SIDEBAR), size(Placement.PANEL), size(Placement.SECONDARY_SIDEBAR)),
        )
    }

    /** Only documents whose type is [restorable] are written; the rest would not survive a restart. */
    fun stage(stage: EditorStage, restorable: (DocumentUri) -> Boolean): JsonObject = buildJsonObject {
        put("axis", stage.axis.name.lowercase())
        put("active", stage.active)
        put("groups", buildJsonArray { stage.groups.forEach { add(group(it, restorable)) } })
    }

    private fun group(g: EditorGroup, restorable: (DocumentUri) -> Boolean): JsonObject {
        val tabs = g.tabs.filter { restorable(it.uri) }
        val keys = tabs.map { it.key }.toSet()
        return buildJsonObject {
            g.active?.takeIf { it in keys }?.let { put("active", it.toString()) }
            put("tabs", buildJsonArray {
                tabs.forEach { add(buildJsonObject { put("uri", it.uri.toString()); put("state", it.state.name.lowercase()) }) }
            })
            put("mru", buildJsonArray { g.mru.filter { it in keys }.forEach { add(JsonPrimitive(it.toString())) } })
        }
    }

    fun stageFrom(element: JsonElement?): EditorStage {
        val o = element as? JsonObject ?: return EditorStage()
        val groups = (o["groups"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::groupFrom) }
            .take(ShellLimits.MAX_GROUPS).ifEmpty { listOf(EditorGroup()) }
        val axis = SplitAxis.entries.firstOrNull { it.name.lowercase() == text(o["axis"]) } ?: SplitAxis.ROW
        val active = ((o["active"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0).coerceIn(groups.indices)
        return EditorStage(groups, active, axis)
    }

    private fun groupFrom(o: JsonObject): EditorGroup {
        val tabs = (o["tabs"] as? JsonArray).orEmpty().mapNotNull { t ->
            val tab = t as? JsonObject
            val uri = tab?.let { text(it["uri"]) }?.let(DocumentUri::parse)
            uri?.let { Tab(it, TAB_STATES[text(tab["state"])] ?: TabState.KEPT) }
        }
        val mru = (o["mru"] as? JsonArray).orEmpty().mapNotNull { text(it)?.let(DocumentUri::parse) }
        return EditorGroup.sanitized(tabs, text(o["active"])?.let(DocumentUri::parse), mru)
    }

    fun text(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
