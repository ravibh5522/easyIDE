package dev.easyide.app.ui.kit

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.props.Feel
import dev.easyide.app.ui.props.LocalFeel
import dev.easyide.app.ui.props.LocalMetrics
import dev.easyide.app.ui.props.LocalMotion
import dev.easyide.app.ui.props.Motion
import dev.easyide.app.ui.props.RadiusScale
import dev.easyide.app.ui.props.SpaceScale
import dev.easyide.app.ui.props.ControlScale
import dev.easyide.app.ui.props.UiMetrics
import dev.easyide.app.ui.theme.EditorColors
import dev.easyide.app.ui.theme.editorColors

/**
 * The one accessor kit components and screens read design values through
 * (docs/ui-redesign/properties.md 6): `Kit.space.m`, `Kit.radius.s`, `Kit.colors.accent`.
 * Values follow the appearance properties, so nothing that appears in them is a literal at a
 * call site. The kit does not read `MaterialTheme.colorScheme`.
 */
object Kit {
    val metrics: UiMetrics @Composable @ReadOnlyComposable get() = LocalMetrics.current
    val space: SpaceScale @Composable @ReadOnlyComposable get() = LocalMetrics.current.space
    val radius: RadiusScale @Composable @ReadOnlyComposable get() = LocalMetrics.current.radius
    val control: ControlScale @Composable @ReadOnlyComposable get() = LocalMetrics.current.control
    val colors: EditorColors @Composable @ReadOnlyComposable get() = editorColors
    val text: KitText @Composable @ReadOnlyComposable get() = LocalKitText.current
    val motion: Motion @Composable @ReadOnlyComposable get() = LocalMotion.current
    val feel: Feel @Composable @ReadOnlyComposable get() = LocalFeel.current

    /** Widest a settings-style page grows on a large window; a phone fills the width. */
    val contentMax: Dp = 640.dp

    /** Hairline width; dividers and borders are drawn with it, never with a literal. */
    val hairline: Dp = 1.dp

    /** The accent bar of a selected row, tab or navigation item. */
    val marker: Dp = 2.dp
}

/**
 * The hit box of an isolated control (a button, a dialog action): at least the touch floor,
 * 44dp on a phone and 40dp on a window wide enough for a pointer (density.md 2), however small the
 * visible control is. Controls inside dense rows and toolbars use [kitHitSlop] instead.
 */
@Composable
fun Modifier.kitTouchFloor(): Modifier {
    val floor = Kit.metrics.touchFloor
    return defaultMinSize(minWidth = floor, minHeight = floor)
}

/**
 * The layout box of a small visual control in a dense row or toolbar (an icon button, a switch):
 * [dev.easyide.app.ui.props.ControlScale.hitBox], 32dp dense and 44dp otherwise. The drawn size
 * does not change. The touch region grows beyond this box to the touch floor through
 * [TouchFloorConfiguration], which the theme installs, so a 28dp button in a 28dp row is still
 * reachable at 40dp without making the row taller. Put it before `clickable` so the click covers it.
 */
@Composable
fun Modifier.kitHitSlop(): Modifier {
    val box = Kit.control.hitBox
    return defaultMinSize(minWidth = box, minHeight = box)
}

/** A stable id for tests and on-device checks; derived from the component's role, not its text. */
fun Modifier.kitTag(id: String): Modifier = testTag("kit:$id")
