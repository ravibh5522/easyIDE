package dev.easyide.ext.cli

import dev.easyide.extensions.json.stringOrNull
import dev.easyide.extensions.manifest.ExtensionDescriptor
import dev.easyide.extensions.view.Payload
import dev.easyide.extensions.view.PlanEnv
import dev.easyide.extensions.view.PlanNode
import dev.easyide.extensions.view.PlanResult
import dev.easyide.extensions.view.ViewData
import dev.easyide.extensions.view.ViewDocument
import dev.easyide.extensions.view.ViewPlan
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `expect.views` of a `test/<name>.json` scenario (extension-ui.md section 8, item 3): renders a pack's view or document
 * with the app's own [ViewPlan] and asserts on the tree, so a pack tests its screens without a device.
 *
 * ```json
 * { "view": "acme.docker.containers", "data": { "containers": [...] },
 *   "types": ["list", "iconButton"], "texts": ["web", "Stop web"], "absent": ["Start web"], "rows": 2 }
 * ```
 * `view` is a view id or a document type; `data` is merged over the view's own `state`; `types` and `texts` must all
 * be present somewhere in the tree (list, table and tree rows are realised), `absent` texts must not be, `rows` is the
 * row count of the first data component.
 */
internal object ViewScenario {

    fun check(d: ExtensionDescriptor, expected: JsonArray): List<String> = expected.filterIsInstance<JsonObject>().flatMap { one(d, it) }

    private fun one(d: ExtensionDescriptor, e: JsonObject): List<String> {
        val id = e["view"]?.stringOrNull ?: return listOf("a views expectation needs a \"view\"")
        val doc = document(d, id) ?: return listOf("no view or document type '$id' with a schema in this pack")
        val data = (e["data"] as? JsonObject)?.let { ViewData.merge(doc.state, it) }
        val merged = (data as? ViewData.Written.Data)?.data ?: doc.state
        if (data is ViewData.Written.Rejected) return listOf("$id: ${data.reason}")
        val plan = ViewPlan.build(doc, merged, PlanEnv(log = {}))
        val root = (plan as? PlanResult.Ready)?.root ?: return listOf("$id: view unavailable: ${(plan as PlanResult.Unavailable).reason}")
        val nodes = flatten(root)
        val problems = ArrayList<String>()
        strings(e["types"]).forEach { t -> if (nodes.none { it.type.wire == t }) problems += "$id: no '$t' component rendered (rendered: ${nodes.map { it.type.wire }.distinct()})" }
        val texts = nodes.flatMap(::texts)
        strings(e["texts"]).forEach { t -> if (texts.none { it.contains(t) }) problems += "$id: text '$t' not rendered (rendered: ${texts.filter { it.isNotEmpty() }.take(TEXT_PREVIEW)})" }
        strings(e["absent"]).forEach { t -> if (texts.any { it.contains(t) }) problems += "$id: text '$t' is rendered but expected absent" }
        (e["rows"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()?.let { want ->
            val got = nodes.firstNotNullOfOrNull { rowCount(it) }
            if (got != want) problems += "$id: expected $want rows, got ${got ?: "no data component"}"
        }
        return problems
    }

    private fun document(d: ExtensionDescriptor, id: String): ViewDocument? =
        d.contributes.views.firstOrNull { it.id == id }?.schema ?: d.contributes.documents.firstOrNull { it.type == id }?.body

    private fun strings(e: JsonElement?): List<String> = (e as? JsonArray)?.mapNotNull { it.stringOrNull }.orEmpty()

    /** Every node, with the rows of list, tree and table components realised as nodes of their own. */
    private fun flatten(n: PlanNode): List<PlanNode> {
        val rows = when (val p = n.payload) {
            is Payload.Rows -> (0 until p.rows.count).mapNotNull { p.rows.at(it) }
            is Payload.TreeRows -> (0 until p.rows.count).mapNotNull { p.rows.at(it)?.node }
            else -> emptyList()
        }
        return listOf(n) + (n.children + rows + listOfNotNull(n.empty)).flatMap(::flatten)
    }

    private fun texts(n: PlanNode): List<String> = n.text.values.toList() + when (val p = n.payload) {
        is Payload.TableRows -> p.columns.map { it.title } + (0 until p.rows.count).flatMap { p.rows.at(it).orEmpty() }
        is Payload.Lines -> p.lines
        is Payload.Messages -> p.messages.map { it.text }
        else -> emptyList()
    }

    private fun rowCount(n: PlanNode): Int? = when (val p = n.payload) {
        is Payload.Rows -> p.rows.count
        is Payload.TreeRows -> p.rows.count
        is Payload.TableRows -> p.rows.count
        is Payload.Lines -> p.lines.size
        is Payload.Messages -> p.messages.size
        else -> null
    }

    private const val TEXT_PREVIEW = 12
}
