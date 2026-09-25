package dev.easyide.app.ui.props

import androidx.compose.runtime.staticCompositionLocalOf

/*
 * Static locals: metrics, motion and feel change rarely (a settings edit), and when they do
 * the whole tree should recompose once. Editor text is not re-laid out by an accent change.
 */

val LocalMetrics = staticCompositionLocalOf { UiMetrics.DEFAULT }
val LocalMotion = staticCompositionLocalOf { Motion.DEFAULT }
val LocalFeel = staticCompositionLocalOf { Feel.DEFAULT }
