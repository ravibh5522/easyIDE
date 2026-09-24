package dev.easyide.app.ui.kit

import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize

/**
 * Compose pads the touch region of every clickable up to `minimumTouchTargetSize` without changing
 * layout. The platform default is 48dp; the theme replaces it with the touch floor of the width
 * class (44dp phone, 40dp wide), so dense controls stay reachable at the size density.md promises.
 */
internal class TouchFloorConfiguration(base: ViewConfiguration, floor: Dp) : ViewConfiguration by base {
    override val minimumTouchTargetSize: DpSize = DpSize(floor, floor)
}
