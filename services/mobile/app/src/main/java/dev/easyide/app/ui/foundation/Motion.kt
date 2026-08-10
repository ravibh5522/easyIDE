package dev.easyide.app.ui.foundation

import android.animation.ValueAnimator
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion durations in one place so timing is consistent, and so honouring the
 * system "remove animations" accessibility setting is a single decision rather
 * than something each animation site has to remember.
 * See docs/design-system/arch.md "Animation".
 */
object MotionTokens {
    const val DURATION_SHORT_MS = 150
    const val DURATION_MEDIUM_MS = 250
    const val DURATION_LONG_MS = 400

    /** Entering elements decelerate; it reads as arriving rather than snapping. */
    val EnterEasing = LinearOutSlowInEasing

    /** Everything else uses the standard symmetric curve. */
    val StandardEasing = FastOutSlowInEasing
}

/**
 * False when the user has disabled animations system-wide. Read this instead of
 * assuming animation is always wanted - ignoring it is an accessibility bug,
 * not a style preference.
 */
val LocalMotionEnabled = staticCompositionLocalOf { true }

/** Queries the platform animator scale; false means "remove animations" is on. */
fun systemMotionEnabled(): Boolean =
    runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)

/**
 * A tween that collapses to an instant [snap] when motion is disabled, so
 * callers get the same API either way and no animation site needs a branch.
 */
@Composable
@ReadOnlyComposable
fun <T> motionSpec(
    durationMs: Int = MotionTokens.DURATION_MEDIUM_MS,
    easing: androidx.compose.animation.core.Easing = MotionTokens.StandardEasing,
): FiniteAnimationSpec<T> =
    if (LocalMotionEnabled.current) tween(durationMillis = durationMs, easing = easing) else snap()

/** Duration to use for APIs that take a raw duration rather than a spec. */
@Composable
@ReadOnlyComposable
fun motionDuration(durationMs: Int = MotionTokens.DURATION_MEDIUM_MS): Int =
    if (LocalMotionEnabled.current) durationMs else 0
