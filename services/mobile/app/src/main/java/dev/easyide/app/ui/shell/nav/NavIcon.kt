package dev.easyide.app.ui.shell.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.easyide.app.extensions.adapters.ShellContributions
import dev.easyide.app.ui.icons.iconFor
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.shell.IconRef
import dev.easyide.app.ui.shell.ext.ExtIcons

/** The glyph of a navigation item: a pack's own SVG (`ext:` names) once read, else the icon resolver's token. */
@Composable
fun navIcon(ref: IconRef): ImageVector =
    if (ref.name.startsWith(ShellContributions.EXT_ICON_PREFIX)) ExtIcons.of(ref, Kit.colors.plainText) else iconFor(ref.name)
