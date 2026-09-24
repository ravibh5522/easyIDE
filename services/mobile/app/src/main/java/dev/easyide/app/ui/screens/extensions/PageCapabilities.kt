package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone
import dev.easyide.extensions.manifest.Source

/**
 * The Capabilities tab: everything the extension declares, in plain words, with whether the
 * user's approval covers it. Revoking one switches the extension off until it is granted again
 * (enablement rule 5); a built-in is trusted with what it declares, so it has nothing to revoke.
 */
@Composable
internal fun CapabilitiesTab(row: ExtensionRow, actions: PageActions) {
    val d = row.loaded?.descriptor
    val builtIn = row.pkg.source == Source.BUILT_IN
    val lines = capabilityLines(d?.capabilities?.items?.mapTo(HashSet()) { it.id }.orEmpty(), row.pkg.approvedCapabilities, builtIn)
    if (lines.isEmpty()) {
        KitEmptyState(EmptyArt.Prompt, stringResource(R.string.ext_capabilities_none))
        return
    }
    val note = stringResource(if (builtIn) R.string.extui_capabilities_builtin else R.string.extui_capabilities_revoke_note)
    KitSection(stringResource(R.string.ext_capabilities), description = note) {
        lines.forEach { line ->
            Column {
                KitRow(
                    title = line.id,
                    mono = true,
                    id = "capability-row",
                    trailing = { CapabilityAction(line, actions) },
                )
                BasicText(
                    capabilityPrompt(line.id, row.pkg.envId),
                    Modifier.padding(start = Kit.space.l, end = Kit.space.l, bottom = Kit.space.s),
                    style = Kit.text.body.copy(color = Kit.colors.textMuted),
                )
            }
        }
    }
    BasicText(
        stringResource(R.string.ext_install_not_isolated),
        Modifier.padding(start = Kit.space.l, end = Kit.space.l, top = Kit.space.s),
        style = Kit.text.caption.copy(color = Kit.colors.textMuted),
    )
}

@Composable
private fun CapabilityAction(line: CapabilityLine, actions: PageActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
        when {
            line.revocable -> KitButton(stringResource(R.string.extui_revoke), { actions.onGrant(line.id, false) }, style = KitButtonStyle.Danger)
            !line.granted -> {
                KitTag(stringResource(R.string.extui_not_granted), tone = Tone.Warning)
                KitButton(stringResource(R.string.extui_grant), { actions.onGrant(line.id, true) }, style = KitButtonStyle.Secondary)
            }
            else -> KitTag(stringResource(R.string.extui_granted), tone = Tone.Success)
        }
    }
}
