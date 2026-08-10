package dev.easyide.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.foundation.LocalMotionEnabled
import dev.easyide.app.ui.foundation.MotionTokens

/**
 * Shared empty state. The icon breathes slowly to keep an otherwise dead screen
 * feeling alive - suppressed entirely when the user has disabled animations,
 * where a looping animation would be actively unwanted.
 *
 * Sizing is the caller's: pass `fillMaxSize()` to centre it in a whole screen,
 * or leave it unconstrained to embed it above other content. It must not force
 * its own height, or siblings in a Column get pushed off-screen.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    val motionEnabled = LocalMotionEnabled.current
    val scale by if (motionEnabled) {
        rememberInfiniteTransition(label = "empty-state-pulse").animateFloat(
            initialValue = MIN_SCALE,
            targetValue = MAX_SCALE,
            animationSpec = infiniteRepeatable(
                animation = tween(PULSE_DURATION_MS, easing = MotionTokens.StandardEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "empty-state-scale",
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .widthIn(max = MAX_CONTENT_WIDTH.dp)
                .padding(24.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(ICON_BACKGROUND_SIZE.dp)
                    .scale(scale)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(ICON_SIZE.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            action?.invoke()
        }
    }
}

private const val MIN_SCALE = 0.94f
private const val MAX_SCALE = 1.06f
private const val PULSE_DURATION_MS = 2200
private const val MAX_CONTENT_WIDTH = 420
private const val ICON_BACKGROUND_SIZE = 96
private const val ICON_SIZE = 44
