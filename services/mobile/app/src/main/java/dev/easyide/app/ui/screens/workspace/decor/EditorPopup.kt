package dev.easyide.app.ui.screens.workspace.decor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import dev.easyide.app.ui.theme.editorColors
import kotlin.math.roundToInt

/**
 * The popups open over one editor, most recent last, so Escape closes the topmost first.
 *
 * Needed because an [EditorPopup] is drawn in the editor's own window rather than a
 * `Popup` window: a focusable popup window would take focus from the text field and close
 * the soft keyboard mid-completion, and a non-focusable one never sees key events. Keeping
 * focus in the text field means Escape reaches the editor surface, which asks this host.
 */
@Stable
class EditorPopupHost {
    private val open = ArrayList<State<() -> Unit>>()

    internal fun register(onDismiss: State<() -> Unit>) {
        open += onDismiss
    }

    internal fun unregister(onDismiss: State<() -> Unit>) {
        open.remove(onDismiss)
    }

    /** Dismisses the most recently opened popup; false when none is open. */
    fun dismissTop(): Boolean {
        val top = open.lastOrNull() ?: return false
        top.value()
        return true
    }
}

/** The host of the editor this composition is inside; null outside an editor. */
val LocalEditorPopupHost = staticCompositionLocalOf<EditorPopupHost?> { null }

/** Routes Escape to [host] while focus is anywhere inside the modified subtree. */
internal fun Modifier.dismissPopupsOnEscape(host: EditorPopupHost): Modifier = onPreviewKeyEvent { event ->
    event.type == KeyEventType.KeyDown && event.key == Key.Escape && host.dismissTop()
}

/**
 * A card anchored to a spot in the editor text, kept inside the editor viewport.
 *
 * Place it in the `overlay` slot of `EditorPane`, which fills the viewport. [anchor] is read
 * during layout - typically `{ geometry.caretRect() }` or `{ geometry.rangeRect(s, e) }` -
 * so scrolling or moving the caret re-positions the card without recomposing its content.
 * While the anchor is scrolled out of view nothing is shown; whether that should also
 * dismiss is the caller's policy (hover does, completion does not).
 *
 * Escape (hardware keyboard) and Back dismiss it through [onDismiss]; the caller owns
 * visibility, this composable never hides itself on its own.
 */
@Composable
fun EditorPopup(
    anchor: () -> Rect?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    preferAbove: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = editorColors
    val dismiss = rememberUpdatedState(onDismiss)
    val host = LocalEditorPopupHost.current
    DisposableEffect(host, dismiss) {
        host?.register(dismiss)
        onDispose { host?.unregister(dismiss) }
    }
    BackHandler { dismiss.value() }

    // The anchor is read in the measure block below, never here, so a scroll or caret move
    // re-places the card without recomposing what is inside it.
    Layout(
        content = {
            Surface(
                modifier = modifier,
                color = colors.panel,
                contentColor = colors.plainText,
                shape = RoundedCornerShape(DecorationMetrics.popupRadius),
                border = BorderStroke(DecorationMetrics.popupBorder, colors.panelBorder),
                content = content,
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val rect = anchor()
        val target = rect?.let { PopupAnchor(it.left.roundToInt(), it.top.roundToInt(), it.bottom.roundToInt()) }
        if (target == null || !PopupPlacement.isVisible(target, height)) {
            return@Layout layout(width, height) {}
        }
        val margin = DecorationMetrics.popupMargin.roundToPx()
        val gap = DecorationMetrics.popupAnchorGap.roundToPx()
        val placeable = measurables.single().measure(
            Constraints(
                maxWidth = PopupPlacement.maxWidth(width, margin),
                maxHeight = PopupPlacement.maxHeight(target, height, margin, gap),
            ),
        )
        val position = PopupPlacement.place(
            anchor = target,
            popupWidth = placeable.width,
            popupHeight = placeable.height,
            viewportWidth = width,
            viewportHeight = height,
            margin = margin,
            gap = gap,
            preferAbove = preferAbove,
        )
        layout(width, height) { placeable.place(position.x, position.y) }
    }
}
