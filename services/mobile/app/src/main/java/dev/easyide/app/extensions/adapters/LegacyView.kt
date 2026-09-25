package dev.easyide.app.extensions.adapters

import dev.easyide.extensions.view.PropValue
import dev.easyide.extensions.view.ViewDocument
import dev.easyide.extensions.view.ViewNode
import dev.easyide.extensions.view.ViewTemplate
import dev.easyide.extensions.view.ViewType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * What a view without a `schema` shows (extension-ui.md section 8: older packs keep working): the items its
 * `viewData` (or a provider's `setViewData`) supplies, as a list of `label` / `description` rows. The data lives
 * under `items`, which is where a `list` or `tree` result of `easyide.viewData` is written.
 */
object LegacyView {
    const val ITEMS = "items"

    private fun text(s: String) = PropValue.Text((ViewTemplate.parse(s) as ViewTemplate.Parse.Ok).template)

    fun document(viewId: String, hasData: Boolean): ViewDocument {
        val label = ViewNode(ViewType.TEXT, null, null, mapOf("value" to text("{label}"), "role" to PropValue.Choice("body")), pointer = "/root/item/0")
        val detail = ViewNode(ViewType.TEXT, null, null, mapOf("value" to text("{description}"), "role" to PropValue.Choice("caption")), pointer = "/root/item/1")
        val row = ViewNode(
            ViewType.COLUMN, null, null, emptyMap(), listOf(label, detail), pointer = "/root/item",
        )
        val empty = ViewNode(ViewType.EMPTY_STATE, null, null, mapOf("message" to text(if (hasData) "Nothing to show" else "This view has no content")), pointer = "/root/empty")
        val list = ViewNode(ViewType.LIST, null, null, mapOf("bind" to PropValue.Path(ITEMS)), item = row, empty = empty, pointer = "/root")
        return ViewDocument("$viewId (legacy)", JsonObject(mapOf(ITEMS to JsonArray(emptyList()))), list, nodes = 5, depth = 3)
    }
}
