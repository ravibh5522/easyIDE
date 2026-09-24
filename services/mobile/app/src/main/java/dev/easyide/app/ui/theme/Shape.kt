package dev.easyide.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/**
 * Material shape roles on the [Radius] scale: tight corners for dense chrome
 * (keys, badges, buttons), the larger steps for cards and sheets. Components
 * that want a specific step use `MaterialTheme.shapes.*`, never a literal.
 */
val EasyIdeShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.s),
    medium = RoundedCornerShape(Radius.m),
    large = RoundedCornerShape(Radius.l),
    extraLarge = RoundedCornerShape(Radius.l),
)
