package dev.easyide.extensions.view

import dev.easyide.extensions.action.Action
import dev.easyide.extensions.action.OpenGroup
import dev.easyide.extensions.contrib.CommandIcon
import dev.easyide.extensions.contrib.PackageFile
import dev.easyide.extensions.json.asText
import dev.easyide.extensions.json.numberOrNull
import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.whenclause.ContextLookup
import dev.easyide.extensions.whenclause.WhenEvaluator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** UI-local state a view keeps outside its data: which tree nodes are open and which tab shows (by node key). */
data class ViewUiState(val expanded: Set<String> = emptySet(), val tabs: Map<String, Int> = emptyMap())

/** What planning reads besides the view: the clock for `relative`, the shell's context keys for `when`, a log for skipped nodes. */
class PlanEnv(
    val now: Long = 0,
    val context: ContextLookup = ContextLookup { null },
    val ui: ViewUiState = ViewUiState(),
    val log: (String) -> Unit = {},
)

sealed interface ResolvedTarget {
    data class Command(val id: String, val args: JsonElement?) : ResolvedTarget
    data class Inline(val action: Action, val args: JsonElement?) : ResolvedTarget
    data class Open(val uri: String, val group: OpenGroup) : ResolvedTarget
    data object Local : ResolvedTarget
}

data class ResolvedConfirm(val title: String, val body: String?, val destructive: Boolean)

/** An event binding with everything resolved against the data at the moment it fires. */
data class ResolvedAction(
    val target: ResolvedTarget, val confirm: ResolvedConfirm?, val into: Into?, val before: List<ResolvedEffect>, val after: List<ResolvedEffect>,
)

/**
 * A component's event, bound to the scope it was planned in. [resolve] runs at fire time so `{draft}` reads what the
 * user just typed: the renderer passes such live values as [local], which shadow the data.
 */
class PlannedAction(private val action: ViewAction, private val scope: ViewScope, private val now: Long) {
    fun resolve(local: Map<String, JsonElement> = emptyMap()): ResolvedAction {
        val s = if (local.isEmpty()) scope else ViewScope(JsonObject(local), scope)
        val args = action.args?.resolve(s, now)
        val target = when (val t = action.target) {
            is ActionTarget.Command -> ResolvedTarget.Command(t.id, args)
            is ActionTarget.Inline -> ResolvedTarget.Inline(t.action, args)
            is ActionTarget.Open -> ResolvedTarget.Open(t.uri.resolve(s, now), t.group)
            ActionTarget.Local -> ResolvedTarget.Local
        }
        val confirm = action.confirm?.let { ResolvedConfirm(it.title.resolve(s, now), it.body?.resolve(s, now), it.destructive) }
        fun effects(list: List<Effect>) = list.map { ResolvedEffect(it.op, it.path, it.value?.resolve(s, now)) }
        return ResolvedAction(target, confirm, action.into, effects(action.before), effects(action.after))
    }
}

/** [count] rows realised on demand: a virtualized list asks for the rows on screen, and never all of them. */
class RowSource<T>(val count: Int, private val keyOf: (Int) -> String, private val build: (Int) -> T?) {
    fun key(i: Int): String = keyOf(i)
    fun at(i: Int): T? = build(i)
}

/** [id] is the row's path from the root of the tree: what `ViewUiState.expanded` holds. */
data class TreeRow(val id: String, val depth: Int, val expandable: Boolean, val expanded: Boolean, val node: PlanNode)

data class ChatMessage(val id: String, val role: String, val text: String, val state: String?, val tool: ChatTool?)

/** A tool call card: what ran ([name], [command]), how it ended ([status]) and its [output]. */
data class ChatTool(val name: String, val command: String?, val status: String?, val output: String?)

data class PlanOption(val label: String, val value: JsonElement)

data class PlanColumn(val title: String, val weight: Double, val mono: Boolean)

/** The data a data component draws, read from its `bind` path and bounded by the ring limits. */
sealed interface Payload {
    data class Rows(val rows: RowSource<PlanNode>) : Payload
    data class TreeRows(val rows: RowSource<TreeRow>) : Payload
    data class TableRows(val columns: List<PlanColumn>, val rows: RowSource<List<String>>) : Payload
    data class Series(val values: List<Double>) : Payload
    data class Lines(val lines: List<String>) : Payload
    data class Messages(val messages: List<ChatMessage>) : Payload
}

/**
 * One resolved component. Text, numbers and flags are by prop name; [bind] and [value] are the data path a control
 * edits and what it holds now; [key] is stable across updates, so a recomposition redraws only what changed.
 * [fills] is set when a virtualized body sits inside: the parent then must not scroll, and gives it the space.
 */
class PlanNode(
    val type: ViewType,
    val key: String,
    val text: Map<String, String> = emptyMap(),
    val nums: Map<String, Double> = emptyMap(),
    val flags: Map<String, Boolean> = emptyMap(),
    val icons: Map<String, CommandIcon> = emptyMap(),
    val images: Map<String, PackageFile> = emptyMap(),
    val bind: String? = null,
    val value: JsonElement? = null,
    val children: List<PlanNode> = emptyList(),
    val payload: Payload? = null,
    val empty: PlanNode? = null,
    val options: List<PlanOption> = emptyList(),
    val action: PlannedAction? = null,
    val enabled: Boolean = true,
    val fills: Boolean = false,
)

sealed interface PlanResult {
    data class Ready(val root: PlanNode, val nodes: Int) : PlanResult

    /** The view cannot be drawn: the shell shows "view unavailable: [reason]" and logs it. */
    data class Unavailable(val reason: String) : PlanResult
}

/**
 * The pure step between a [ViewDocument] and pixels (extension-ui.md sections 4.2 and 4.5): bind data, evaluate
 * `when`, resolve templates, enforce the render budget. The Compose renderer draws a [PlanNode] and nothing else, and
 * `easyide-ext test` asserts on the same tree, so what a test sees is what the user sees.
 */
object ViewPlan {
    private val DATA_TYPES = setOf(ViewType.LIST, ViewType.TREE, ViewType.TABLE, ViewType.LOG_STREAM, ViewType.CHAT)
    private val BODY_LAYOUTS = setOf(ViewType.COLUMN, ViewType.TABS, ViewType.TAB, ViewType.SPLIT)

    fun build(doc: ViewDocument, data: JsonObject, env: PlanEnv = PlanEnv()): PlanResult {
        val run = Run(data, env)
        val root = run.node(doc.root, ViewScope.of(data), "", bodyPosition = true)
            ?: return PlanResult.Ready(PlanNode(ViewType.COLUMN, "root"), 0).takeIf { !run.overBudget }
            ?: PlanResult.Unavailable("more than ${ViewLimits.MAX_RENDERED_NODES} components would be drawn")
        if (run.overBudget) return PlanResult.Unavailable("more than ${ViewLimits.MAX_RENDERED_NODES} components would be drawn")
        return PlanResult.Ready(root, run.count)
    }

    private class Run(val data: JsonObject, val env: PlanEnv) {
        var count = 0
        val overBudget: Boolean get() = count > ViewLimits.MAX_RENDERED_NODES

        private fun spend(n: Int = 1): Boolean { count += n; return !overBudget }

        fun node(n: ViewNode, scope: ViewScope, prefix: String, bodyPosition: Boolean): PlanNode? {
            if (overBudget) return null
            if (n.type.spec.availability == Availability.RESERVED) {
                env.log("component '${n.type.wire}' at ${n.pointer} is not available in this app; skipped")
                return null
            }
            if (n.condition != null && !holds(n.condition, scope)) return null
            if (!spend()) return null
            val key = prefix + (n.id ?: n.pointer.substringAfterLast('/'))
            val text = LinkedHashMap<String, String>()
            val nums = LinkedHashMap<String, Double>()
            val flags = LinkedHashMap<String, Boolean>()
            val icons = LinkedHashMap<String, CommandIcon>()
            val images = LinkedHashMap<String, PackageFile>()
            var options = emptyList<PlanOption>()
            for ((name, v) in n.props) when (v) {
                is PropValue.Text -> text[name] = v.template.resolve(scope, env.now)
                is PropValue.Choice -> text[name] = v.value
                is PropValue.Num -> nums[name] = v.value
                is PropValue.Flag -> flags[name] = v.value
                is PropValue.IconRef -> icons[name] = v.icon
                is PropValue.Image -> images[name] = v.file
                is PropValue.Options -> options = v.value(scope, env.now)
                is PropValue.Path, is PropValue.Condition, is PropValue.Paths, is PropValue.Columns, is PropValue.Pattern -> Unit
            }
            val bind = n.path("bind")
            val value = bind?.let { scope[it] }
            val optionsFrom = n.path("optionsFrom")?.let { p -> choicesAt(scope[p], scope) }
            val enabled = n.condition("enabledWhen")?.let { holds(it, scope) } ?: true
            val action = n.action?.let { PlannedAction(it, scope, env.now) }
            val body = bodyPosition && n.type in BODY_LAYOUTS
            val lazy = bodyPosition && n.type in DATA_TYPES
            var fills = lazy
            val children = ArrayList<PlanNode>()
            val (payload, empty) = when (n.type) {
                ViewType.LIST -> list(n, scope, key, lazy)
                ViewType.TREE -> tree(n, scope, key, lazy) to null
                ViewType.TABLE -> table(n, scope, key, lazy)
                ViewType.LOG_STREAM -> logLines(scope[bind.orEmpty()]) to null
                ViewType.CHAT -> messages(scope[bind.orEmpty()]) to null
                ViewType.SPARKLINE -> series(scope[bind.orEmpty()]) to null
                else -> null to null
            }
            val selectedTab = if (n.type == ViewType.TABS) selectedTab(n, key, value) else -1
            n.children.forEachIndexed { i, c ->
                if (n.type == ViewType.TABS && i != selectedTab) return@forEachIndexed
                node(c, scope, "$key/", body || (n.type == ViewType.TAB && bodyPosition))?.let {
                    children += it
                    fills = fills || it.fills
                }
            }
            if (n.type == ViewType.TABS) {
                // The tab strip needs every title, though only the selected tab's content is planned.
                text["titles"] = n.children.joinToString("\u0001") { (it.props["title"] as? PropValue.Text)?.template?.resolve(scope, env.now).orEmpty() }
                nums["selected"] = selectedTab.toDouble()
            }
            return PlanNode(
                n.type, key, text, nums, flags, icons, images, bind, value, children, payload, empty,
                if (optionsFrom != null) optionsFrom else options, action, enabled, fills,
            )
        }

        private fun PropValue.Options.value(scope: ViewScope, now: Long) = options.map { PlanOption(it.label.resolve(scope, now), it.value) }

        private fun choicesAt(v: JsonElement?, scope: ViewScope): List<PlanOption>? = (v as? JsonArray)?.map { e ->
            val o = e as? JsonObject
            if (o != null) PlanOption(o["label"]?.asText() ?: o["value"]?.asText().orEmpty(), o["value"] ?: JsonNull) else PlanOption(e.asText(), e)
        }

        private fun holds(expr: dev.easyide.extensions.whenclause.WhenExpr, scope: ViewScope): Boolean =
            WhenEvaluator.evaluate(expr) { key -> scope[key] ?: env.context[key] }

        private fun selectedTab(n: ViewNode, key: String, bound: JsonElement?): Int {
            val wanted = bound?.numberOrNull?.toInt() ?: env.ui.tabs[key] ?: 0
            return wanted.coerceIn(0, maxOf(n.children.lastIndex, 0))
        }

        // ---- data components -------------------------------------------------------------------------------------

        private fun items(scope: ViewScope, path: String?): List<JsonElement> =
            (path?.let { scope[it] } as? JsonArray)?.take(ViewLimits.MAX_ROWS).orEmpty()

        private fun itemKey(item: JsonElement, keyPath: String, index: Int): String =
            ViewScope.of(item)[keyPath]?.asText()?.takeIf { it.isNotEmpty() } ?: "#$index"

        /** Row keys must be unique for a list to be keyed: a repeated one (bad data) gets a `~n` suffix, so it never collides. */
        private fun uniqueKeys(keys: List<String>): List<String> {
            val seen = HashMap<String, Int>()
            return keys.map { k -> val n = seen.merge(k, 1, Int::plus)!!; if (n == 1) k else "$k~$n" }
        }

        private fun matches(item: JsonElement, query: String, fields: List<String>): Boolean {
            if (query.isBlank()) return true
            val s = ViewScope.of(item)
            val haystack = if (fields.isEmpty()) listOf(item.toString()) else fields.map { s.text(it) }
            return haystack.any { it.contains(query.trim(), ignoreCase = true) }
        }

        private fun list(n: ViewNode, scope: ViewScope, key: String, lazy: Boolean): Pair<Payload, PlanNode?> {
            val query = n.path("query")?.let { scope.text(it) }.orEmpty()
            val all = items(scope, n.path("bind")).filter { matches(it, query, n.paths("filterFields")) }
            val shown = if (lazy) all else all.take(ViewLimits.MAX_INLINE_ROWS)
            val template = n.item!!
            val keyPath = n.path("key") ?: "id"
            val perRow = template.walk().count()
            spend(perRow * minOf(shown.size, if (lazy) ViewLimits.VISIBLE_ROWS_ESTIMATE else shown.size))
            val keys = uniqueKeys(shown.mapIndexed { i, item -> itemKey(item, keyPath, i) })
            val rows = RowSource(shown.size, { keys[it] }) { i ->
                val row = ViewScope.of(data).let { root -> root.child(shown[i]) }
                // Rows spend nothing here: their budget was taken above, for the rows a screen shows.
                Run(data, env).node(template, row, "$key/${keys[i]}/", bodyPosition = false)
            }
            val empty = if (shown.isEmpty()) n.empty?.let { node(it, scope, "$key/", bodyPosition = false) } else null
            return Payload.Rows(rows) to empty
        }

        private fun tree(n: ViewNode, scope: ViewScope, key: String, lazy: Boolean): Payload {
            val template = n.item!!
            val keyPath = n.path("key") ?: "id"
            val childField = n.path("childrenField") ?: "children"
            val flat = ArrayList<TreeRow>()
            fun walk(list: List<JsonElement>, depth: Int, parentKey: String) {
                val siblings = uniqueKeys(list.mapIndexed { i, item -> itemKey(item, keyPath, i) })
                for ((i, item) in list.withIndex()) {
                    if (flat.size >= (if (lazy) ViewLimits.MAX_ROWS else ViewLimits.MAX_INLINE_ROWS)) return
                    val k = parentKey + "/" + siblings[i]
                    val kids = (ViewScope.of(item)[childField] as? JsonArray)?.toList().orEmpty()
                    val open = k in env.ui.expanded
                    val rowScope = ViewScope.of(data).child(item)
                    val planned = Run(data, env).node(template, rowScope, "$key$k/", bodyPosition = false) ?: continue
                    flat += TreeRow(k, depth, kids.isNotEmpty(), open, planned)
                    if (open) walk(kids, depth + 1, k)
                }
            }
            walk(items(scope, n.path("bind")), 0, "")
            spend(minOf(flat.size, if (lazy) ViewLimits.VISIBLE_ROWS_ESTIMATE else flat.size) * template.walk().count())
            return Payload.TreeRows(RowSource(flat.size, { flat[it].node.key }) { flat[it] })
        }

        private fun table(n: ViewNode, scope: ViewScope, key: String, lazy: Boolean): Pair<Payload, PlanNode?> {
            val columns = n.columns("columns")
            val all = items(scope, n.path("bind"))
            val shown = if (lazy) all else all.take(ViewLimits.MAX_INLINE_ROWS)
            val keyPath = n.path("key") ?: "id"
            val keys = uniqueKeys(shown.mapIndexed { i, item -> itemKey(item, keyPath, i) })
            val rows = RowSource(shown.size, { keys[it] }) { i ->
                val s = ViewScope.of(data).child(shown[i])
                columns.map { c -> s[c.field]?.let { v -> c.format?.apply(v, env.now) ?: v.asText() }.orEmpty() }
            }
            spend(minOf(shown.size, if (lazy) ViewLimits.VISIBLE_ROWS_ESTIMATE else shown.size) * columns.size)
            val empty = if (shown.isEmpty()) n.empty?.let { node(it, scope, "$key/", bodyPosition = false) } else null
            return Payload.TableRows(columns.map { PlanColumn(it.title.resolve(scope, env.now), it.weight, it.mono) }, rows) to empty
        }

        private fun logLines(v: JsonElement?): Payload = Payload.Lines(
            when (v) {
                is JsonArray -> v.map { it.asText() }
                is JsonPrimitive -> if (v.isString) v.content.lines() else emptyList()
                else -> emptyList()
            }.takeLast(ViewLimits.LOG_LINES),
        )

        private fun messages(v: JsonElement?): Payload = Payload.Messages(
            (v as? JsonArray).orEmpty().mapIndexedNotNull { i, e ->
                val o = e as? JsonObject ?: return@mapIndexedNotNull null
                val tool = (o["tool"] as? JsonObject)?.let {
                    ChatTool(it["name"]?.stringOrNull.orEmpty(), it["command"]?.stringOrNull, it["status"]?.stringOrNull, it["output"]?.stringOrNull)
                }
                ChatMessage(o["id"]?.asText() ?: "#$i", o["role"]?.stringOrNull ?: "assistant", o["text"]?.stringOrNull.orEmpty(), o["state"]?.stringOrNull, tool)
            }.takeLast(ViewLimits.CHAT_MESSAGES),
        )

        private fun series(v: JsonElement?): Payload = Payload.Series(
            (v as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.numberOrNull }.takeLast(ViewLimits.MAX_INLINE_ROWS * 2),
        )
    }
}
