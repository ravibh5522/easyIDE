package dev.easyide.app.ui.foundation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

/**
 * Forward/back navigation transitions. Direction carries the hierarchy: pushing
 * slides in from the trailing edge, popping reverses it, so the user keeps a
 * sense of depth in a stack that is only one level deep.
 *
 * [motionEnabled] is passed in rather than read from a CompositionLocal because
 * NavHost builds these outside the composition of the destination.
 */
class NavTransitions(private val motionEnabled: Boolean) {

    private val duration: Int
        get() = if (motionEnabled) MotionTokens.DURATION_MEDIUM_MS else 0

    private val slideFraction: (Int) -> Int = { fullWidth -> fullWidth / SLIDE_DIVISOR }

    fun enter(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(animationSpec = tween(duration), initialOffsetX = slideFraction) +
            fadeIn(animationSpec = tween(duration))
    }

    fun exit(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(animationSpec = tween(duration), targetOffsetX = { -slideFraction(it) }) +
            fadeOut(animationSpec = tween(duration))
    }

    fun popEnter(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(animationSpec = tween(duration), initialOffsetX = { -slideFraction(it) }) +
            fadeIn(animationSpec = tween(duration))
    }

    fun popExit(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(animationSpec = tween(duration), targetOffsetX = slideFraction) +
            fadeOut(animationSpec = tween(duration))
    }

    private companion object {
        /** Partial slide: a full-width slide feels sluggish on tablet widths. */
        const val SLIDE_DIVISOR = 6
    }
}
