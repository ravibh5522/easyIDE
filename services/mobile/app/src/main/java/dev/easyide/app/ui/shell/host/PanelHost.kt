package dev.easyide.app.ui.shell.host

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.kitTag

/**
 * The primary panel: the surface a container draws its list on. Docked on a wide window (the
 * caller sizes it) and the whole first screen on a phone. A container nobody registered a renderer
 * for (an extension that went away) shows a message instead of an empty column. Bindings that span
 * the window bring their own insets; a plain panel keeps clear of the status and navigation bars.
 */
@Composable
fun PanelHost(containerId: String?, panels: PanelRendererRegistry, modifier: Modifier = Modifier) {
    val binding = containerId?.let(panels::binding)
    val inset = if (binding?.spansWindow == true) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
    Box(modifier.kitTag("panel").background(Kit.colors.panel).then(inset).focusGroup()) {
        if (binding != null) {
            binding.renderer.Render(Modifier)
        } else {
            KitEmptyState(EmptyArt.Prompt, stringResource(R.string.shell_panel_unavailable))
        }
    }
}
