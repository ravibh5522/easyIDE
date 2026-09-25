package dev.easyide.app.ui.screens.workspace.layout

import androidx.compose.ui.unit.dp

/** Sizes and opacities of the adaptive workspace chrome, in one place. */
object LayoutTokens {

    /** The invisible strip around a splitter line that accepts the drag (12-16dp per the ux-overhaul spec). */
    val splitterGrab = 16.dp

    /** Modal drawer width as a share of the window, capped so it never covers a whole tablet-sized window. */
    const val DRAWER_WIDTH_FRACTION = 0.86f
    val drawerMaxWidth = 360.dp

    /** Scrim over the editor behind a modal drawer. */
    const val SCRIM_ALPHA = 0.5f

    /** Fill behind the status bar's environment pill, as a share of the bar's text colour. */
    const val PILL_ALPHA = 0.14f

    /** Tint laid over a hovered row, tab or button (mouse and trackpad only). */
    const val HOVER_ALPHA = 0.08f

    /** Height of the compact bottom switcher; 56dp is the Material navigation-bar height and clears the 48dp target. */
    val switcherHeight = 56.dp
}
