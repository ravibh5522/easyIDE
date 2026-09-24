package dev.easyide.app.ui.shell.ext

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitChoice
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitSlider
import dev.easyide.app.ui.kit.KitToggle
import dev.easyide.extensions.view.PlanNode
import dev.easyide.extensions.view.ViewType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun InputNode(n: PlanNode, modifier: Modifier, bounded: Boolean) {
    when (n.type) {
        ViewType.BUTTON -> ButtonNode(n, modifier)
        ViewType.ICON_BUTTON -> IconButtonNode(n, modifier)
        ViewType.TOGGLE -> ToggleNode(n, modifier)
        ViewType.FIELD -> FieldNode(n, modifier, ImeAction.Done)
        ViewType.SEARCH -> FieldNode(n, modifier, ImeAction.Search)
        ViewType.SELECT -> SelectNode(n, modifier)
        ViewType.SLIDER -> SliderNode(n, modifier)
        ViewType.FORM -> FormNode(n, modifier, bounded)
        ViewType.COMPOSER -> ComposerNode(n, modifier)
        else -> Unit
    }
}

/**
 * What an event carries beyond the data: `value` (the new value of a control) and the bound [path] set to it, nested
 * so `{form.host}` reads it. Values shadow the data only for this call; the data changes through [ViewCtx.commit].
 */
internal fun localValue(path: String?, value: JsonElement): Map<String, JsonElement> {
    val nested = path?.split('.')?.foldRight<String, JsonElement>(value) { key, inner -> JsonObject(mapOf(key to inner)) }
    return buildMap {
        put("value", value)
        if (path != null && nested is JsonObject) putAll(nested)
    }
}

@Composable
private fun ButtonNode(n: PlanNode, modifier: Modifier) {
    val ctx = LocalViewCtx.current
    val action = n.action
    val style = when (n.text["style"]) {
        "primary" -> KitButtonStyle.Primary
        "ghost" -> KitButtonStyle.Ghost
        "danger" -> KitButtonStyle.Danger
        else -> KitButtonStyle.Secondary
    }
    KitButton(
        n.text["label"].orEmpty(), { action?.let { ctx.fire(it, emptyMap()) } }, modifier,
        style = style, icon = n.icons["icon"]?.let { rememberExtIcon(it) }, enabled = n.enabled && action != null,
    )
}

@Composable
private fun IconButtonNode(n: PlanNode, modifier: Modifier) {
    val ctx = LocalViewCtx.current
    val action = n.action
    val icon = n.icons["icon"] ?: return
    KitIconButton(rememberExtIcon(icon), n.text["label"].orEmpty(), { action?.let { ctx.fire(it, emptyMap()) } }, modifier, toneOf(n.text["tone"]), n.enabled && action != null)
}

@Composable
private fun ToggleNode(n: PlanNode, modifier: Modifier) {
    val ctx = LocalViewCtx.current
    val bind = n.bind ?: return
    val checked = (n.value as? JsonPrimitive)?.content == "true"
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Kit.space.m), verticalAlignment = Alignment.CenterVertically) {
        BasicText(n.text["label"].orEmpty(), Modifier.weight(1f), style = Kit.type.bodyMedium.copy(color = Kit.colors.plainText))
        KitToggle(checked, { on ->
            val value = JsonPrimitive(on)
            ctx.commit(bind, value)
            n.action?.let { ctx.fire(it, localValue(bind, value)) }
        }, enabled = n.enabled)
    }
}

/**
 * A text field bound to data. It edits a local copy and commits after a quiet period, so a filter follows typing without
 * the view being planned again on every key; an external change to the bound value (an effect clearing a draft) replaces
 * the text. Submitting commits first, so the action reads what was typed.
 */
@Composable
private fun FieldNode(n: PlanNode, modifier: Modifier, ime: ImeAction) {
    val ctx = LocalViewCtx.current
    val bind = n.bind ?: return
    val current = (n.value as? JsonPrimitive)?.content.orEmpty()
    var text by remember(n.key) { mutableStateOf(current) }
    LaunchedEffect(current) { if (current != text) text = current }
    LaunchedEffect(text) {
        delay(ExtViewTokens.COMMIT_DEBOUNCE_MS)
        if (text != current) ctx.commit(bind, JsonPrimitive(text))
    }
    val pattern = remember(n.key, n.text["validate"]) { n.text["validate"]?.let { runCatching { Regex(it) }.getOrNull() } }
    val invalid = pattern != null && text.isNotEmpty() && !pattern.containsMatchIn(text)
    val submit = {
        val value = JsonPrimitive(text)
        ctx.commit(bind, value)
        n.action?.let { ctx.fire(it, localValue(bind, value)) }
        Unit
    }
    KitField(
        text, { text = it }, modifier.fillMaxWidth(),
        label = n.text["label"], hint = n.text["hint"] ?: if (n.type == ViewType.SEARCH) stringResource(R.string.extview_search_hint) else null,
        error = if (invalid) stringResource(R.string.extview_invalid) else null,
        singleLine = n.flags["multiline"] != true, mono = n.flags["mono"] == true, enabled = n.enabled,
        keyboard = KeyboardOptions(imeAction = ime), keyboardActions = KeyboardActions(onDone = { submit() }, onSearch = { submit() }),
    )
}

@Composable
private fun SelectNode(n: PlanNode, modifier: Modifier) {
    val ctx = LocalViewCtx.current
    val bind = n.bind ?: return
    val selected = n.options.firstOrNull { it.value == n.value } ?: n.options.firstOrNull() ?: return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
        n.text["label"]?.let { BasicText(it, style = Kit.type.labelSmall.copy(color = Kit.colors.textMuted)) }
        KitChoice(n.options, selected, { it.label }, { option ->
            ctx.commit(bind, option.value)
            n.action?.let { ctx.fire(it, localValue(bind, option.value)) }
        })
    }
}

@Composable
private fun SliderNode(n: PlanNode, modifier: Modifier) {
    val ctx = LocalViewCtx.current
    val bind = n.bind ?: return
    val min = n.nums["min"]?.toFloat() ?: 0f
    val max = n.nums["max"]?.toFloat() ?: 1f
    val value = (n.value as? JsonPrimitive)?.content?.toFloatOrNull() ?: min
    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            BasicText(n.text["label"].orEmpty(), style = Kit.type.bodyMedium.copy(color = Kit.colors.plainText))
            BasicText(formatNumber(value), style = Kit.type.bodySmall.copy(color = Kit.colors.textMuted))
        }
        KitSlider(value, { v ->
            val json = JsonPrimitive(v.toDouble())
            ctx.commit(bind, json)
            n.action?.let { ctx.fire(it, localValue(bind, json)) }
        }, min..max, step = n.nums["step"]?.toFloat() ?: 0f, enabled = n.enabled)
    }
}

private fun formatNumber(v: Float): String = if (v == Math.floor(v.toDouble()).toFloat()) v.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, v)

@Composable
private fun FormNode(n: PlanNode, modifier: Modifier, bounded: Boolean) {
    val ctx = LocalViewCtx.current
    val action = n.action
    Column(modifier.fillMaxWidth().padding(Kit.space.l), verticalArrangement = Arrangement.spacedBy(Kit.space.m)) {
        n.children.forEach { child -> androidx.compose.runtime.key(child.key) { PlanNodeView(child, Modifier, bounded) } }
        if (action != null) {
            KitButton(n.text["submitLabel"] ?: stringResource(R.string.extview_submit), { ctx.fire(action, emptyMap()) }, Modifier.fillMaxWidth(), enabled = n.enabled)
        }
    }
}

/**
 * The composer of a conversation: a multi-line field that grows to a few lines and a send button. Ctrl+Enter sends from a
 * hardware keyboard; Enter is a new line. The text is the bound draft, committed on send so `{draft}` reads it, and an
 * field empties after every send, and an effect that clears the draft empties it too.
 */
@Composable
private fun ComposerNode(n: PlanNode, modifier: Modifier) {
    val ctx = LocalViewCtx.current
    val bind = n.bind ?: return
    val action = n.action ?: return
    val current = (n.value as? JsonPrimitive)?.content.orEmpty()
    var text by remember(n.key) { mutableStateOf(current) }
    LaunchedEffect(current) { if (current != text) text = current }
    val send = {
        if (text.isNotBlank()) {
            val value = JsonPrimitive(text)
            ctx.commit(bind, value)
            ctx.fire(action, localValue(bind, value))
            // The message is on its way: the field empties whether or not the view also clears its draft.
            text = ""
        }
    }
    Row(modifier.fillMaxWidth().padding(Kit.space.s), horizontalArrangement = Arrangement.spacedBy(Kit.space.s), verticalAlignment = Alignment.Bottom) {
        KitField(
            text, { text = it },
            Modifier.weight(1f).onPreviewKeyEvent { e ->
                val ctrlEnter = e.type == KeyEventType.KeyDown && e.key == Key.Enter && e.isCtrlPressed
                if (ctrlEnter) send()
                ctrlEnter
            },
            hint = n.text["hint"] ?: stringResource(R.string.extchat_hint), singleLine = false, enabled = n.enabled,
        )
        KitButton(n.text["sendLabel"] ?: stringResource(R.string.extchat_send), { send() }, enabled = n.enabled && text.isNotBlank())
    }
}
