package dev.easyide.app.ui.shell.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import dev.easyide.app.ui.shell.nav.NavPlacement

/**
 * Lays the navigation surface beside or under the content and settles the insets between them.
 * The surface applies the system-bar insets it sits under; the content is told they are consumed so
 * a screen inside it (which applies its own) never pads for the same bar twice. On a phone the bar
 * steps aside while the software keyboard is up: the input dock takes that place (shell-model.md 6.2).
 */
@Composable
fun AdaptiveScaffold(
    placement: NavPlacement,
    nav: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    when (placement) {
        NavPlacement.BOTTOM -> Column(modifier.fillMaxSize()) {
            val barShown = !keyboardUp
            val consumed = if (barShown) Modifier.consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)) else Modifier
            Box(Modifier.weight(1f).then(consumed)) { content(Modifier) }
            if (barShown) nav()
        }
        NavPlacement.RAIL_START -> Row(modifier.fillMaxSize()) {
            nav()
            Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))) { content(Modifier) }
        }
        NavPlacement.RAIL_END -> Row(modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.End))) { content(Modifier) }
            nav()
        }
    }
}
