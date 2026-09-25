package dev.easyide.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import dev.easyide.app.ui.props.UiMetrics

/**
 * Material shape roles on the radius scale of the active [UiMetrics]: tight corners for dense
 * chrome (keys, badges, buttons), the larger steps for cards and sheets. A Material widget that
 * is not yet on the kit still follows the corners property through these.
 */
fun easyIdeShapes(metrics: UiMetrics): Shapes = Shapes(
    extraSmall = RoundedCornerShape(metrics.radius.xs),
    small = RoundedCornerShape(metrics.radius.s),
    medium = RoundedCornerShape(metrics.radius.m),
    large = RoundedCornerShape(metrics.radius.l),
    extraLarge = RoundedCornerShape(metrics.radius.l),
)
