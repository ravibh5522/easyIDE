package dev.easyide.app.ui.kit

import androidx.compose.ui.unit.dp

/**
 * Fixed sizes of the input primitives that no appearance property changes. Spacing, radius, row
 * heights and control heights come from `Kit.space/radius/control`; what is here is the geometry
 * of one control (a toggle glyph, a twistie slot) or a column rule.
 */
internal object KitSizes {
    /** Buttons of a dialog on compact width, where they sit under a thumb. */
    val buttonLarge = 48.dp

    /** Check box and radio side, and the switch track height. */
    val glyph = 20.dp
    val switchWidth = 36.dp
    val switchThumb = 14.dp

    /** Fixed leading columns of a row (U-DEN-02): the disclosure twistie, then the icon slot. */
    val twistieSlot = 16.dp
    val leadingSlot = 20.dp

    /** A description narrower than this is dropped instead of showing a stub of ellipsis. */
    val descriptionMin = 40.dp

    /** Below this width a two-column settings row stacks its control under its label. */
    val twoColumnStackBelow = 480.dp

    /** The label column of a two-column row is this share of the row, clamped to the two bounds. */
    const val LABEL_COLUMN_SHARE = 0.45f
    val labelColumnMin = 160.dp
    val labelColumnMax = 360.dp

    val dialogMaxWidth = 480.dp
    val menuMinWidth = 180.dp
    val menuMaxWidth = 320.dp

    /** A long menu (a commit's branches) scrolls past this instead of running off a tablet's window. */
    val menuMaxHeight = 480.dp
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
