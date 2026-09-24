package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone
import dev.easyide.extensions.manifest.InstallScope

/** The detail dialog of a Browse row: everything the signed entry says, the device's pin, and Install / Update. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RegistryDetail(item: BrowseItem, onInstall: (BrowseItem) -> Unit, onForgetPin: (BrowseItem) -> Unit, onDismiss: () -> Unit) {
    val e = item.entry
    val confirm = when (item.action()) {
        BrowseAction.Install -> stringResource(R.string.reg_install)
        BrowseAction.Update -> stringResource(R.string.reg_update, e?.version?.toString().orEmpty())
        BrowseAction.Installed, BrowseAction.Unavailable -> null
    }
    KitDialog(
        title = item.displayName,
        onDismiss = onDismiss,
        confirm = confirm?.let { KitAction(it) { onInstall(item) } },
        dismiss = KitAction(stringResource(R.string.ext_prompt_close), onDismiss),
    ) {
        DialogMono(item.id)
        FlowRow(Modifier.padding(top = Kit.space.s), horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
            item.registryId?.let { KitTag(stringResource(R.string.reg_badge, it)) }
            item.installedVersion?.let { KitTag(stringResource(R.string.reg_installed, it)) }
        }
        item.description?.let { DialogText(it) }
        DialogText(stringResource(R.string.reg_detail_versions, item.versions.joinToString()), muted = true)
        if (e == null) {
            KitBanner(stringResource(R.string.reg_incompatible), Modifier.padding(top = Kit.space.s), Tone.Warning)
        } else {
            DialogText(stringResource(R.string.reg_detail_license, e.license), muted = true)
            if (e.categories.isNotEmpty()) DialogText(stringResource(R.string.reg_detail_categories, e.categories.joinToString()), muted = true)
            DialogText(stringResource(if (e.scope == InstallScope.GLOBAL) R.string.ext_scope_global else R.string.ext_scope_environment), muted = true)
            DialogText(stringResource(R.string.reg_detail_published, e.publishedAt.toString()), muted = true)
            DialogHeading(stringResource(R.string.ext_capabilities))
            if (e.capabilities.isEmpty()) DialogText(stringResource(R.string.ext_capabilities_none), muted = true)
            e.capabilities.forEach { DialogText(capabilityPrompt(it, null)) }
            DialogText(stringResource(R.string.reg_detail_signed, e.signature.keyId), muted = true, modifier = Modifier.padding(top = Kit.space.m))
            DialogMono(stringResource(R.string.reg_detail_package, e.size, e.sha256))
        }
        item.pinnedKey?.let { pin ->
            DialogText(stringResource(R.string.reg_pinned, item.id.substringBefore('.'), pin), muted = true, modifier = Modifier.padding(top = Kit.space.m))
            KitBanner(stringResource(R.string.reg_forget_pin_warning), Modifier.padding(vertical = Kit.space.s), Tone.Warning)
            KitButton(stringResource(R.string.reg_forget_pin), { onForgetPin(item) }, style = KitButtonStyle.Danger)
        }
    }
}
