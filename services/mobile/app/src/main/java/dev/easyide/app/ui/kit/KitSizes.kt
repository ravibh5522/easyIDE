package dev.easyide.app.ui.kit

import androidx.compose.ui.unit.dp

/**
 * Fixed sizes of the input primitives that no appearance property changes. Spacing, radius and
 * row heights come from `Kit.space/radius/control`; what is here is the geometry of one control
 * (a button's face, a tag, a toggle glyph), always drawn inside a 44dp hit box.
 */
internal object KitSizes {
    /** Visible height of a button; the hit box stays at the touch floor. */
    val button = 40.dp

    /** Buttons of a dialog on compact width, where they sit under a thumb. */
    val buttonLarge = 48.dp

    val tag = 20.dp

    /** Check box and radio side, and the switch track height. */
    val glyph = 20.dp
    val switchWidth = 36.dp
    val switchThumb = 14.dp

    val dialogMaxWidth = 480.dp
    val menuMinWidth = 180.dp
    val menuMaxWidth = 320.dp
}

/** Alphas of the tone step drawn over a control for hover and press, and of an idle track. */
internal object KitStateLayer {
    const val HOVER = 0.08f
    const val PRESSED = 0.14f
    const val TRACK = 0.3f

    fun alphaFor(pressed: Boolean, hovered: Boolean): Float = when {
        pressed -> PRESSED
        hovered -> HOVER
        else -> 0f
    }
}
