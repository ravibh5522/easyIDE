package dev.easyide.app.ui.shell.ext

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.easyide.app.R
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitProgress
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.screens.workspace.MarkdownBlockView
import dev.easyide.app.ui.screens.workspace.parseMarkdown
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.extensions.view.Payload
import dev.easyide.extensions.view.PlanNode
import dev.easyide.extensions.view.ViewType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** The tone names of the schema; anything else is neutral. */
internal fun toneOf(name: String?): Tone = Tone.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Tone.Neutral

@Composable
internal fun ContentNode(n: PlanNode, modifier: Modifier) {
    when (n.type) {
        ViewType.TEXT -> TextNode(n, modifier)
        ViewType.MARKDOWN -> MarkdownNode(n, modifier)
        ViewType.KEY_VALUE -> KeyValueNode(n, modifier)
        ViewType.TAG -> KitTag(n.text["value"].orEmpty(), modifier, toneOf(n.text["tone"]))
        ViewType.STATUS_DOT -> StatusDot(n, modifier)
        ViewType.PROGRESS -> ProgressNode(n, modifier)
        ViewType.ICON -> IconNode(n, modifier)
        ViewType.IMAGE -> ImageNode(n, modifier)
        ViewType.CODE -> CodeBlock(n.text["value"].orEmpty(), modifier)
        ViewType.BANNER -> KitBanner(n.text["value"].orEmpty(), modifier, toneOf(n.text["tone"]))
        ViewType.EMPTY_STATE -> EmptyStateNode(n, modifier)
        ViewType.DIFF -> DiffBlock(n.text["value"].orEmpty(), modifier)
        ViewType.SPARKLINE -> Sparkline(n, modifier)
        else -> Unit
    }
}

@Composable
private fun TextNode(n: PlanNode, modifier: Modifier) {
    val colors = Kit.colors
    val tone = n.text["tone"]?.let(::toneOf)
    val base = when (n.text["role"]) {
        "title" -> Kit.text.heading.copy(color = colors.plainText)
        "caption" -> Kit.text.caption.copy(color = colors.textMuted)
        "mono" -> Kit.text.mono.copy(color = colors.plainText)
        else -> Kit.text.body.copy(color = colors.plainText)
    }
    val style = if (tone != null) base.copy(color = tone.content(colors)) else base
    val lines = n.nums["maxLines"]?.toInt() ?: Int.MAX_VALUE
    BasicText(n.text["value"].orEmpty(), modifier, style = style, maxLines = lines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun MarkdownNode(n: PlanNode, modifier: Modifier) {
    val colors = Kit.colors
    val text = n.text["value"].orEmpty()
    val blocks = remember(text, colors) { parseMarkdown(text, colors) }
    Column(modifier) { blocks.forEach { MarkdownBlockView(it) } }
}

@Composable
private fun KeyValueNode(n: PlanNode, modifier: Modifier) {
    val colors = Kit.colors
    val mono = n.flags["mono"] == true
    Row(modifier.fillMaxWidth().padding(vertical = Kit.space.xs), horizontalArrangement = Arrangement.spacedBy(Kit.space.m)) {
        BasicText(n.text["label"].orEmpty(), Modifier.weight(KEY_SHARE), style = Kit.text.caption.copy(color = colors.textMuted))
        val value = if (mono) Kit.text.mono else Kit.text.body
        BasicText(n.text["value"].orEmpty(), Modifier.weight(1f - KEY_SHARE), style = value.copy(color = colors.plainText))
    }
}

private const val KEY_SHARE = 0.4f

@Composable
private fun StatusDot(n: PlanNode, modifier: Modifier) {
    val color = toneOf(n.text["tone"]).content(Kit.colors)
    val label = n.text["label"].orEmpty()
    Box(modifier.size(ExtViewTokens.dot).background(color).semantics { contentDescription = label })
}

@Composable
private fun ProgressNode(n: PlanNode, modifier: Modifier) {
    val fraction = n.text["value"]?.toFloatOrNull()?.coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
        n.text["label"]?.takeIf { it.isNotEmpty() }?.let { BasicText(it, style = Kit.text.caption.copy(color = Kit.colors.textMuted)) }
        KitProgress(fraction, Modifier.fillMaxWidth(), toneOf(n.text["tone"] ?: "accent"))
    }
}

@Composable
private fun IconNode(n: PlanNode, modifier: Modifier) {
    val icon = n.icons["name"] ?: return
    val tint = toneOf(n.text["tone"]).content(Kit.colors)
    val label = n.text["label"]
    Image(rememberExtIcon(icon), label, modifier.size(IconSize.l), colorFilter = ColorFilter.tint(tint))
}

/** A pack image, decoded off the main thread; while it loads, or if it cannot be read, its description stands in. */
@Composable
private fun ImageNode(n: PlanNode, modifier: Modifier) {
    val file = n.images["src"] ?: return
    val label = n.text["label"].orEmpty()
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file.hostPath) {
        value = withContext(Dispatchers.IO) { decode(file.hostPath) }
    }
    val height = n.nums["height"]?.let { Dp(it.toFloat()) } ?: ExtViewTokens.imageDefault
    val shown = bitmap
    if (shown != null) {
        Image(shown, label, modifier.heightIn(max = height))
    } else {
        BasicText(stringResource(R.string.extview_image_missing), modifier.semantics { contentDescription = label }, style = Kit.text.caption.copy(color = Kit.colors.textMuted))
    }
}

/** I/O boundary: a pack's image file; unreadable or undecodable gives null. */
private fun decode(path: String): androidx.compose.ui.graphics.ImageBitmap? = try {
    BitmapFactory.decodeFile(File(path).path)?.asImageBitmap()
} catch (e: IOException) {
    null
}

@Composable
private fun EmptyStateNode(n: PlanNode, modifier: Modifier) {
    val ctx = LocalViewCtx.current
    val action = n.action
    val label = n.text["actionLabel"]
    KitEmptyState(
        EmptyArt.Prompt, n.text["message"].orEmpty(), modifier,
        action = if (action != null && !label.isNullOrEmpty()) KitAction(label) { ctx.fire(action, emptyMap()) } else null,
    )
}

/** Read-only monospace text in a raised block that scrolls both ways, so a long line or a long file never widens the view. */
@Composable
internal fun CodeBlock(text: String, modifier: Modifier) {
    val colors = Kit.colors
    val shape = RoundedCornerShape(Kit.radius.s)
    Box(
        modifier.fillMaxWidth().heightIn(max = ExtViewTokens.codeMax).clip(shape).background(colors.raised)
            .border(Kit.hairline, colors.panelBorder, shape).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
            .padding(Kit.space.m),
    ) {
        BasicText(text, style = Kit.text.mono.copy(color = colors.plainText))
    }
}

/** A unified diff as text: added lines in the success tone, removed in the error tone, hunk headers in the info tone. */
@Composable
private fun DiffBlock(text: String, modifier: Modifier) {
    val colors = Kit.colors
    val shape = RoundedCornerShape(Kit.radius.s)
    Column(
        modifier.fillMaxWidth().heightIn(max = ExtViewTokens.codeMax).clip(shape).background(colors.raised)
            .border(Kit.hairline, colors.panelBorder, shape).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
            .padding(Kit.space.m),
    ) {
        text.lineSequence().forEach { line ->
            val tone = when {
                line.startsWith("@@") -> Tone.Info
                line.startsWith("+") && !line.startsWith("+++") -> Tone.Success
                line.startsWith("-") && !line.startsWith("---") -> Tone.Danger
                else -> null
            }
            BasicText(line.ifEmpty { " " }, style = Kit.text.mono.copy(color = tone?.content(colors) ?: colors.plainText))
        }
    }
}

@Composable
private fun Sparkline(n: PlanNode, modifier: Modifier) {
    val values = (n.payload as? Payload.Series)?.values.orEmpty()
    val color = toneOf(n.text["tone"] ?: "accent").content(Kit.colors)
    val label = n.text["label"].orEmpty()
    val stroke = Kit.marker
    Canvas(modifier.fillMaxWidth().size(ExtViewTokens.sparkline).semantics { contentDescription = label }) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val span = (values.max() - lo).takeIf { it > 0 } ?: 1.0
        val path = Path()
        values.forEachIndexed { i, v ->
            val p = Offset(size.width * i / (values.size - 1), size.height - (size.height * ((v - lo) / span)).toFloat())
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, color, style = Stroke(stroke.toPx()))
    }
}
