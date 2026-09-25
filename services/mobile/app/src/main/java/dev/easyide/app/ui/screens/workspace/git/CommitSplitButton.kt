package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.icons.iconFor
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.app.ui.kit.buttonPaint
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.collectFlags
import dev.easyide.app.ui.kit.kitFocusRing
import dev.easyide.app.ui.kit.kitStateLayer
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.theme.IconSize

/** The label of each split-button entry. */
@Composable
internal fun modeLabel(mode: CommitMode): String = when (mode) {
    CommitMode.COMMIT -> stringResource(R.string.git_commit)
    CommitMode.AMEND -> stringResource(R.string.gitui_commit_amend)
    CommitMode.COMMIT_PUSH -> stringResource(R.string.gitui_commit_push)
    CommitMode.COMMIT_SYNC -> stringResource(R.string.gitui_commit_sync)
}

/** The entries of the dropdown: commit, amend, a divider, then the two that need a remote (disabled, with the reason, without one). */
@Composable
internal fun commitModeItems(canRun: (CommitMode) -> Boolean, hasRemote: Boolean, onPick: (CommitMode) -> Unit): List<KitMenuItem> {
    val reason = stringResource(R.string.gitui_needs_remote)
    fun entry(mode: CommitMode, label: String) =
        KitMenuItem.Action(label, { onPick(mode) }, enabled = canRun(mode) && (hasRemote || !mode.needsRemote), hint = if (mode.needsRemote && !hasRemote) reason else null)
    return listOf(
        entry(CommitMode.COMMIT, stringResource(R.string.git_commit)),
        entry(CommitMode.AMEND, stringResource(R.string.gitui_commit_amend)),
        KitMenuItem.Divider,
        entry(CommitMode.COMMIT_PUSH, stringResource(R.string.gitui_commit_push)),
        entry(CommitMode.COMMIT_SYNC, stringResource(R.string.gitui_commit_sync)),
    )
}

/**
 * VS Code's commit button: the accent fill across the pane, the primary action with a check and its label,
 * then a chevron segment behind a hairline that opens the other commit actions. The primary follows the last
 * chosen entry ([mode]); [menu] lists them. The whole button is the disabled tone while [enabled] is false, but the
 * chevron stays reachable so a mode can be picked before the message is typed.
 */
@Composable
internal fun CommitSplitButton(
    label: String,
    enabled: Boolean,
    onCommit: () -> Unit,
    menu: List<KitMenuItem>,
    modifier: Modifier = Modifier,
) {
    val paint = buttonPaint(KitButtonStyle.Primary, enabled, Kit.colors)
    val fill = paint.fill ?: Kit.colors.accent
    val shape = RoundedCornerShape(Kit.metrics.radiusFor(Kit.radius.xs, Kit.control.buttonHeight))
    val more = stringResource(R.string.gitui_commit_more)
    var open by remember { mutableStateOf(false) }
    val primary = remember { MutableInteractionSource() }
    val chevron = remember { MutableInteractionSource() }
    val primaryFlags = primary.collectFlags()
    val chevronFlags = chevron.collectFlags()

    Row(
        modifier.kitTag("commit-split").fillMaxWidth().height(IntrinsicSize.Min).defaultMinSize(minHeight = Kit.control.buttonHeight).clip(shape).background(fill),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).fillMaxHeight()
                .clickable(primary, null, enabled = enabled, role = Role.Button, onClick = onCommit)
                .kitStateLayer(primaryFlags, enabled, paint.content)
                .kitFocusRing(primaryFlags.focused, shape)
                .padding(horizontal = Kit.control.hPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Kit.space.s, Alignment.CenterHorizontally),
        ) {
            Image(Icons.Filled.Check, null, Modifier.size(IconSize.s), colorFilter = ColorFilter.tint(paint.content))
            BasicText(label, style = Kit.text.title.copy(color = paint.content), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(Kit.hairline).fillMaxHeight().padding(vertical = Kit.space.xs).background(paint.content.copy(alpha = DIVIDER_ALPHA)))
        Box(
            Modifier.width(Kit.control.buttonHeight).fillMaxHeight()
                .clickable(chevron, null, role = Role.Button) { open = true }
                .kitStateLayer(chevronFlags, true, paint.content)
                .kitFocusRing(chevronFlags.focused, shape)
                .semantics { contentDescription = more },
            contentAlignment = Alignment.Center,
        ) {
            Image(iconFor("chevron_down"), null, Modifier.size(IconSize.m), colorFilter = ColorFilter.tint(paint.content))
            KitMenu(open, { open = false }, menu)
        }
    }
}

/** The hairline between the two halves shows through the fill at this strength. */
private const val DIVIDER_ALPHA = 0.4f
