package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.KitToggle
import dev.easyide.app.ui.kit.Tone
import dev.easyide.extensions.manifest.Source

/** What the person can do to the open extension; the page wires these to the view model. */
internal class PageActions(
    val onEnabled: (Boolean) -> Unit,
    val onUninstall: () -> Unit,
    /** Null when no newer signed version is known. */
    val onUpdate: (() -> Unit)?,
    /** Null when no earlier version is kept. */
    val onRollback: (() -> Unit)?,
    val onHide: (InspectorLine, Boolean) -> Unit,
    val onMove: (InspectorLine, Int) -> Unit,
    val onGrant: (capabilityId: String, granted: Boolean) -> Unit,
    /** Null when the host cannot open shell targets: the Settings link is then not offered. */
    val onOpenTarget: ((String) -> Unit)?,
)

/**
 * The compact header: name with the destructive action at its end, then `id@version` in mono with the
 * source and state tags on one line, the description, what needs attention (revoked, an update), and
 * the enable switch as one row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PageHeader(row: ExtensionRow, item: ExtensionListItem, revokedReason: String?, updateTo: String?, actions: PageActions) {
    val space = Kit.space
    val gutter = Kit.control.hPad
    Column(Modifier.padding(start = gutter, end = gutter, top = space.m), verticalArrangement = Arrangement.spacedBy(space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.s)) {
            BasicText(
                item.name,
                Modifier.weight(1f).semantics { heading() },
                style = Kit.text.display.copy(color = Kit.colors.plainText),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.source != Source.BUILT_IN) KitButton(stringResource(R.string.ext_uninstall), actions.onUninstall, style = KitButtonStyle.Danger)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(space.s), verticalArrangement = Arrangement.spacedBy(space.xs), itemVerticalAlignment = Alignment.CenterVertically) {
            BasicText("${item.id}@${item.version}", style = Kit.text.mono.copy(color = Kit.colors.textMuted))
            KitTag(sourceTag(item.source))
            item.tags.forEach { KitTag(tagText(it), tone = it.tone) }
        }
        item.description?.let { BasicText(it, style = Kit.text.body.copy(color = Kit.colors.plainText)) }
        BasicText(stateLabel(row), style = Kit.text.caption.copy(color = Kit.colors.textMuted))
    }
    revokedReason?.let { KitBanner(stringResource(R.string.ext_revoked_reason, it), Modifier.padding(top = space.s), Tone.Danger) }
    if (updateTo != null && actions.onUpdate != null) {
        KitBanner(
            stringResource(R.string.reg_update_available, updateTo),
            Modifier.padding(top = space.s),
            Tone.Info,
            KitAction(stringResource(R.string.extui_update), actions.onUpdate),
        )
    }
    if (item.toggleable) {
        KitSection(title = null) {
            KitRow(
                title = stringResource(R.string.extui_enabled),
                onClick = { actions.onEnabled(!item.enabled) },
                id = "extension-enabled",
                trailing = { KitToggle(item.enabled, onCheckedChange = null) },
            )
        }
    }
}
