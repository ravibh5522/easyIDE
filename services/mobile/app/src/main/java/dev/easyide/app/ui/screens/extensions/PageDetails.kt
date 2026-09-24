package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.extensions.manifest.InstallScope

/** A label with its value at the end, in mono: versions, ids and paths are code-like strings. */
@Composable
private fun FactRow(label: String, value: String) {
    KitRow(
        title = label,
        trailing = { BasicText(value, style = Kit.text.monoSmall.copy(color = Kit.colors.textMuted)) },
    )
}

/** The Details tab: who published it, under what licence, where it came from, and what it is doing. */
@Composable
internal fun DetailsTab(row: ExtensionRow, lines: List<InspectorLine>, actions: PageActions) {
    val d = row.loaded?.descriptor
    row.problem?.errors?.forEach { KitBanner(it.toString(), Modifier.padding(top = Kit.space.m), Tone.Danger) }
    row.loaded?.warnings?.forEach { KitBanner(it.toString(), Modifier.padding(top = Kit.space.m), Tone.Warning) }
    KitSection(stringResource(R.string.extui_section_details), description = trustText(row.pkg.source)) {
        FactRow(stringResource(R.string.extui_fact_publisher), row.id.substringBefore('.'))
        d?.license?.let { FactRow(stringResource(R.string.extui_fact_license), it) }
        val scope = if (d?.scope == InstallScope.ENVIRONMENT) row.pkg.envId ?: stringResource(R.string.extui_scope_environment) else stringResource(R.string.extui_scope_global)
        FactRow(stringResource(R.string.extui_fact_scope), scope)
        d?.categories?.takeIf { it.isNotEmpty() }?.let { FactRow(stringResource(R.string.extui_fact_categories), it.joinToString()) }
    }
    val openTarget = actions.onOpenTarget
    if (openTarget != null && contributesSettings(lines)) {
        KitSection(title = null, description = stringResource(R.string.extui_settings_note)) {
            KitRow(
                title = stringResource(R.string.extui_settings_open),
                onClick = { openTarget(settingsTarget(row.id)) },
                id = "extension-settings",
                trailing = { Image(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(IconSize.l), colorFilter = ColorFilter.tint(Kit.colors.textMuted)) },
            )
        }
    }
}

/** The Versions tab: what is installed, the retained version a rollback returns to, and an update. */
@Composable
internal fun VersionsTab(row: ExtensionRow, item: ExtensionListItem, updateTo: String?, actions: PageActions) {
    KitSection(stringResource(R.string.extui_section_versions), description = if (actions.onRollback == null && actions.onUpdate == null) stringResource(R.string.extui_versions_none) else null) {
        FactRow(stringResource(R.string.extui_version_installed), item.version)
        row.rollbackTo?.let { v ->
            KitRow(
                title = stringResource(R.string.ext_rollback, v),
                onClick = actions.onRollback,
                id = "extension-rollback",
            )
        }
        if (updateTo != null) {
            KitRow(
                title = stringResource(R.string.reg_update, updateTo),
                onClick = actions.onUpdate,
                id = "extension-update",
            )
        }
    }
}
