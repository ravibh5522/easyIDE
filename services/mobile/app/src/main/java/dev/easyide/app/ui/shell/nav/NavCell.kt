package dev.easyide.app.ui.shell.nav

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.kitPressable
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.shell.host.ShellTokens

/**
 * One cell of the bottom bar or rail: glyph, optional label, badge. The active cell wears the
 * block marker on the edge that faces the content (identity.md 2.1) and the accent on its glyph;
 * an inactive one is the chrome's icon colour. The whole cell is the hit box.
 */
@Composable
internal fun NavCell(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    showLabel: Boolean,
    placement: NavPlacement,
    badge: NavBadgeValue?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String,
) {
    val colors = Kit.colors
    val tint = if (selected) colors.accent else colors.activityIcon
    val badgeText = badge?.let { if (it is NavBadgeValue.Count) NavRules.badgeText(it.n) else null }
    val badgeDescription = when {
        badge is NavBadgeValue.Dot -> stringResource(R.string.shell_badge_dot)
        badgeText != null -> stringResource(R.string.shell_badge_count, badgeText)
        else -> null
    }
    Box(
        modifier
            .kitTag(tag)
            .kitPressable(onClick, role = Role.Tab)
            .navMarker(selected, placement)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = listOfNotNull(title, badgeDescription).joinToString(", ")
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Kit.space.xxs)) {
            Box {
                Image(icon, null, Modifier.size(Kit.control.railIcon), colorFilter = ColorFilter.tint(tint))
                if (badge != null) Badge(badgeText, Modifier.align(Alignment.TopEnd))
            }
            if (showLabel) {
                BasicText(
                    title,
                    Modifier.padding(horizontal = Kit.space.xs),
                    style = Kit.text.label.copy(color = tint),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A dot when [text] is null, else the count in a small accent pill. */
@Composable
private fun Badge(text: String?, modifier: Modifier) {
    val colors = Kit.colors
    if (text == null) {
        Box(modifier.size(ShellTokens.badgeDot).background(colors.accent, RoundedCornerShape(Kit.radius.xs)))
        return
    }
    Box(
        modifier.defaultMinSize(ShellTokens.badgeCount, ShellTokens.badgeCount).background(colors.accent, RoundedCornerShape(Kit.radius.xs)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, Modifier.padding(horizontal = Kit.space.xxs), style = Kit.text.label.copy(color = colors.onAccent), maxLines = 1)
    }
}

/** The accent block on the edge of a selected cell that faces the content: top of the bar, inner edge of the rail. */
@Composable
private fun Modifier.navMarker(selected: Boolean, placement: NavPlacement): Modifier {
    if (!selected) return this
    val color = Kit.colors.accent
    val thickness = Kit.marker
    return drawBehind {
        val t = thickness.toPx()
        when (placement) {
            NavPlacement.BOTTOM -> drawRect(color, Offset.Zero, Size(size.width, t))
            NavPlacement.RAIL_START -> drawRect(color, Offset.Zero, Size(t, size.height))
            NavPlacement.RAIL_END -> drawRect(color, Offset(size.width - t, 0f), Size(t, size.height))
        }
    }
}
