package dev.easyide.app.extensions.wasm

import dev.easyide.app.extensions.adapters.StatusItem
import dev.easyide.extensions.action.InputBoxRequest
import dev.easyide.extensions.action.MessageRequest
import dev.easyide.extensions.action.MessageSeverity
import dev.easyide.extensions.action.PickItem
import dev.easyide.extensions.action.PromptPort
import dev.easyide.extensions.action.QuickPickRequest
import dev.easyide.extensions.action.UiPort as ActionUiPort
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.contrib.StatusBarAlignment
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.extwasm.ErrorCode
import dev.easyide.extwasm.HostCallException
import dev.easyide.extwasm.host.UiPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** A status bar item a WASM extension set at runtime (`ui.setStatusBarItem`). */
data class DynamicStatusItem(val item: StatusItem, val priority: Int)

/**
 * UI that WASM extensions publish at runtime, beside their static contributions: status
 * bar items (merged into the workspace status bar with the contributed ones) and view data.
 * No contributed view is rendered anywhere yet, so [viewData] is kept for when one is.
 * Everything of an extension is dropped when its instance goes ([clear]).
 */
class WasmUiState {
    private val status = MutableStateFlow<Map<Pair<String, String>, DynamicStatusItem>>(emptyMap())
    private val views = MutableStateFlow<Map<Pair<String, String>, JsonElement>>(emptyMap())

    /** Every live item, left then right, higher priority first. */
    val statusItems: StateFlow<Map<Pair<String, String>, DynamicStatusItem>> = status.asStateFlow()

    /** `(extensionId, viewId) -> items` last set. */
    val viewData: StateFlow<Map<Pair<String, String>, JsonElement>> = views.asStateFlow()

    fun setStatus(extensionId: String, item: DynamicStatusItem?, id: String) =
        status.update { if (item == null) it - (extensionId to id) else it + ((extensionId to id) to item) }

    fun setView(extensionId: String, viewId: String, items: JsonElement) = views.update { it + ((extensionId to viewId) to items) }

    fun clear(extensionId: String) {
        status.update { m -> m.filterKeys { it.first != extensionId } }
        views.update { m -> m.filterKeys { it.first != extensionId } }
    }

    /** The items in display order, as [dev.easyide.app.extensions.adapters.StatusItems] orders contributed ones. */
    fun ordered(): List<StatusItem> = StatusBarAlignment.entries.flatMap { side ->
        status.value.values.filter { it.item.alignment == side }.sortedByDescending { it.priority }.map { it.item }
    }
}

/**
 * `ui.*`: prompts go through the same [PromptPort] and stage reveal as L1 actions (the
 * workspace's prompt host renders them); status items and view data land in [state].
 * Titles default to the extension's display name, so the user sees who is asking.
 */
class WasmUiPort(
    private val prompts: PromptPort,
    private val stages: ActionUiPort,
    private val state: WasmUiState,
    private val displayName: (String) -> String,
) : UiPort {

    override suspend fun showMessage(extensionId: String, args: JsonObject): JsonElement? {
        val text = args.string(TEXT) ?: throw HostCallException(ErrorCode.E_ARGS, "\"text\" must be a string")
        val severity = args.string(SEVERITY)?.let { s -> MessageSeverity.entries.firstOrNull { it.wire == s } } ?: MessageSeverity.INFO
        val actions = (args[ACTIONS] as? JsonArray).orEmpty().mapNotNull { a ->
            (a as? JsonPrimitive)?.takeIf { it.isString }?.content ?: (a as? JsonObject)?.string(TITLE)
        }
        val owner = ExtensionId.parse(extensionId) ?: throw HostCallException(ErrorCode.E_INTERNAL, "invalid extension id")
        return prompts.showMessage(MessageRequest(owner, text, severity, actions))?.let(::JsonPrimitive)
    }

    override suspend fun showQuickPick(extensionId: String, args: JsonObject): JsonElement? {
        val items = (args[ITEMS] as? JsonArray ?: throw HostCallException(ErrorCode.E_ARGS, "\"items\" must be an array")).map { i ->
            when {
                i is JsonPrimitive && i.isString -> PickItem(i.content, null, i)
                i is JsonObject -> {
                    val label = i.string(LABEL) ?: throw HostCallException(ErrorCode.E_ARGS, "every item needs a \"label\"")
                    PickItem(label, i.string(DESCRIPTION), i[VALUE] ?: JsonPrimitive(label))
                }
                else -> throw HostCallException(ErrorCode.E_ARGS, "items must be strings or {label, description?, value?}")
            }
        }
        val many = (args[CAN_PICK_MANY] as? JsonPrimitive)?.booleanOrNull == true
        val title = args.string(TITLE) ?: displayName(extensionId)
        val picked = prompts.quickPick(QuickPickRequest(title, items, args.string(PLACE_HOLDER), many)) ?: return null
        return if (many) JsonArray(picked) else picked.firstOrNull()
    }

    override suspend fun showInputBox(extensionId: String, args: JsonObject): JsonElement? {
        val password = (args[PASSWORD] as? JsonPrimitive)?.booleanOrNull == true
        val request = InputBoxRequest(args.string(TITLE) ?: displayName(extensionId), args.string(PROMPT), args.string(VALUE), args.string(PLACE_HOLDER), null, password)
        return prompts.inputBox(request)?.let(::JsonPrimitive)
    }

    /** `{id, text, tooltip?, command?, alignment?, priority?}`; an empty or missing text removes the item. */
    override suspend fun setStatusBarItem(extensionId: String, args: JsonObject) {
        val id = args.string(ID) ?: throw HostCallException(ErrorCode.E_ARGS, "\"id\" must be a string")
        val text = args.string(TEXT)?.takeIf { it.isNotBlank() }
        if (text == null) {
            state.setStatus(extensionId, null, id)
            return
        }
        val alignment = args.string(ALIGNMENT)?.let { a -> StatusBarAlignment.entries.firstOrNull { it.wire == a } } ?: StatusBarAlignment.LEFT
        val priority = (args[PRIORITY] as? JsonPrimitive)?.intOrNull ?: 0
        val item = StatusItem(id, text, args.string(TOOLTIP), args.string(COMMAND), alignment, owner(extensionId))
        state.setStatus(extensionId, DynamicStatusItem(item, priority), id)
    }

    override suspend fun setViewData(extensionId: String, viewId: String, items: JsonElement) = state.setView(extensionId, viewId, items)

    override suspend fun revealStage(extensionId: String, args: JsonObject) {
        val stage = args.string(STAGE) ?: throw HostCallException(ErrorCode.E_ARGS, "\"stage\" must be a string")
        stages.revealStage(stage, args.string(VIEW), (args[FOCUS] as? JsonPrimitive)?.booleanOrNull ?: true)
    }

    private fun owner(extensionId: String): Owner = ExtensionId.parse(extensionId)?.let(Owner::Ext) ?: Owner.BuiltIn

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val TEXT = "text"
        const val SEVERITY = "severity"
        const val ACTIONS = "actions"
        const val TITLE = "title"
        const val ITEMS = "items"
        const val LABEL = "label"
        const val DESCRIPTION = "description"
        const val VALUE = "value"
        const val CAN_PICK_MANY = "canPickMany"
        const val PLACE_HOLDER = "placeHolder"
        const val PASSWORD = "password"
        const val PROMPT = "prompt"
        const val ID = "id"
        const val TOOLTIP = "tooltip"
        const val COMMAND = "command"
        const val ALIGNMENT = "alignment"
        const val PRIORITY = "priority"
        const val STAGE = "stage"
        const val VIEW = "view"
        const val FOCUS = "focus"
    }
}
