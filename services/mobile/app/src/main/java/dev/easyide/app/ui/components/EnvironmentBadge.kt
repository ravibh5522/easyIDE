package dev.easyide.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.foundation.motionSpec
import dev.easyide.sandbox.model.EnvironmentState

/**
 * Shows an environment's provisioning state and, when shared, how many other
 * projects use it - the sharing model is only useful if it is visible.
 * Colors come from the theme so all six themes stay correct.
 */
@Composable
fun EnvironmentBadge(
    label: String,
    state: EnvironmentState,
    sharedWithCount: Int,
    modifier: Modifier = Modifier,
) {
    val container = state.containerColor()
    val animatedContainer by animateColorAsState(
        targetValue = container,
        animationSpec = motionSpec(),
        label = "environment-badge-color",
    )

    Surface(
        modifier = modifier,
        color = animatedContainer,
        contentColor = state.contentColor(),
        shape = RoundedCornerShape(BADGE_CORNER_DP.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            if (sharedWithCount > 0) {
                Icon(
                    imageVector = Icons.Filled.Group,
                    contentDescription = null,
                    modifier = Modifier.size(SHARED_ICON_DP.dp),
                )
                Text(text = "+$sharedWithCount", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun EnvironmentState.containerColor(): Color = when (this) {
    EnvironmentState.READY -> MaterialTheme.colorScheme.secondaryContainer
    EnvironmentState.PROVISIONING -> MaterialTheme.colorScheme.tertiaryContainer
    EnvironmentState.FAILED -> MaterialTheme.colorScheme.errorContainer
    EnvironmentState.NOT_PROVISIONED -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun EnvironmentState.contentColor(): Color = when (this) {
    EnvironmentState.READY -> MaterialTheme.colorScheme.onSecondaryContainer
    EnvironmentState.PROVISIONING -> MaterialTheme.colorScheme.onTertiaryContainer
    EnvironmentState.FAILED -> MaterialTheme.colorScheme.onErrorContainer
    EnvironmentState.NOT_PROVISIONED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private const val BADGE_CORNER_DP = 8
private const val SHARED_ICON_DP = 14
