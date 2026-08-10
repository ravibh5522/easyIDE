package dev.easyide.app.ui.screens.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.ui.components.EmptyState
import dev.easyide.app.ui.foundation.LocalMotionEnabled
import dev.easyide.app.ui.foundation.MotionTokens

/**
 * First-run welcome. Content rises in on entry so the first screen of the app
 * does not simply appear fully formed - skipped entirely when the user has
 * animations turned off.
 *
 * The battery-optimization and permission steps this needs to grow
 * (docs/sandbox-runtime/arch.md SS7 - not optional) are pending the runtime.
 */
@Composable
fun OnboardingScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionEnabled = LocalMotionEnabled.current
    var visible by remember { mutableStateOf(!motionEnabled) }

    LaunchedEffect(Unit) { visible = true }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(MotionTokens.DURATION_LONG_MS)) +
                slideInVertically(
                    animationSpec = tween(MotionTokens.DURATION_LONG_MS, easing = MotionTokens.EnterEasing),
                    initialOffsetY = { full -> full / SLIDE_DIVISOR },
                ),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = MAX_CONTENT_WIDTH_DP.dp)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                EmptyState(
                    icon = Icons.Filled.Terminal,
                    title = stringResource(R.string.onboarding_title),
                    body = stringResource(R.string.onboarding_body),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_continue))
                }
                Text(
                    text = stringResource(R.string.onboarding_footnote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private const val MAX_CONTENT_WIDTH_DP = 480
private const val SLIDE_DIVISOR = 8
