package dev.easyide.app.ui.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import dev.easyide.app.ui.theme.IconSize

/** The disclosure state of a tree row or a section header. [Leaf] keeps the slot empty so titles still align. */
enum class Twistie { Leaf, Collapsed, Expanded }

/** The fixed 16dp disclosure column (U-DEN-02): the chevron is drawn at the icon size and centred in it. */
@Composable
internal fun TwistieSlot(state: Twistie, tint: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(KitSizes.twistieSlot), Alignment.Center) {
        val icon = when (state) {
            Twistie.Leaf -> return@Box
            Twistie.Collapsed -> Icons.AutoMirrored.Filled.KeyboardArrowRight
            Twistie.Expanded -> Icons.Filled.KeyboardArrowDown
        }
        Image(icon, null, Modifier.size(IconSize.s), colorFilter = ColorFilter.tint(tint))
    }
}
