package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.data.settings.ImportMode
import dev.easyide.app.data.settings.ImportPreview
import dev.easyide.app.data.settings.SettingsPolicy
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitToggle
import dev.easyide.app.ui.kit.ToggleKind
import dev.easyide.app.ui.kit.Tone

/**
 * Profiles list (LLD 14): switch by tapping, create (empty or a copy of the active one), rename and
 * delete. `default` and the active profile cannot be renamed or deleted, which
 * [dev.easyide.app.data.settings.ProfileManager] enforces too.
 */
@Composable
fun ProfilesDialog(
    profiles: List<String>,
    active: String,
    onSwitch: (String) -> Unit,
    onCreate: (name: String, copyFrom: String?) -> Unit,
    onRename: (from: String, to: String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var copyActive by remember { mutableStateOf(true) }
    var renaming by remember { mutableStateOf<String?>(null) }
    val all = listOf(SettingsPolicy.DEFAULT_PROFILE) + profiles
    val valid = SettingsPolicy.PROFILE_NAME.matches(newName.trim()) && newName.trim() !in all
    val creating = renaming == null

    KitDialog(
        title = stringResource(R.string.settings_profiles_title),
        onDismiss = onDismiss,
        confirm = KitAction(stringResource(R.string.action_done), onDismiss),
    ) {
        all.forEach { name ->
            val mutable = name != SettingsPolicy.DEFAULT_PROFILE && name != active
            KitRow(
                title = name,
                mono = true,
                leading = { KitToggle(name == active, null, kind = ToggleKind.Radio) },
                trailing = {
                    if (mutable) {
                        Row {
                            KitIconButton(Icons.Filled.Edit, stringResource(R.string.settings_profile_rename), { renaming = name; newName = name })
                            KitIconButton(Icons.Filled.DeleteOutline, stringResource(R.string.settings_profile_delete), { onDelete(name) })
                        }
                    }
                },
                onClick = { onSwitch(name) },
            )
        }
        KitField(
            value = newName,
            onValueChange = { newName = it },
            label = stringResource(if (creating) R.string.settings_profile_name else R.string.settings_profile_new_name),
            error = if (newName.isNotEmpty() && !valid) stringResource(R.string.settings_profile_name_invalid) else null,
            mono = true,
            modifier = Modifier.padding(top = Kit.space.m),
        )
        if (creating) {
            KitRow(
                title = stringResource(R.string.settings_profile_copy_active, active),
                leading = { KitToggle(copyActive, null, kind = ToggleKind.Check) },
                onClick = { copyActive = !copyActive },
            )
        }
        KitButton(
            stringResource(if (creating) R.string.settings_profile_create else R.string.settings_profile_rename),
            {
                val from = renaming
                if (from != null) onRename(from, newName) else onCreate(newName, active.takeIf { copyActive })
                renaming = null
                newName = ""
            },
            style = KitButtonStyle.Secondary,
            enabled = valid,
        )
    }
}

/** What an import would do, before anything is written (LLD 15 "preview"). Replace is the destructive way, so it is not the primary. */
@Composable
fun ImportPreviewDialog(preview: ImportPreview, onConfirm: (ImportMode) -> Unit, onDismiss: () -> Unit) {
    val invalid = preview.bundle.diagnostics.size
    KitDialog(
        title = stringResource(R.string.settings_import_title),
        onDismiss = onDismiss,
        confirm = KitAction(stringResource(R.string.settings_import_merge)) { onConfirm(ImportMode.MERGE) },
        dismiss = KitAction(stringResource(R.string.action_cancel), onDismiss),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
            BodyText(pluralStringResource(R.plurals.settings_import_settings, preview.settingCount, preview.settingCount))
            BodyText(pluralStringResource(R.plurals.settings_import_keybindings, preview.keybindingCount, preview.keybindingCount))
            if (preview.profileNames.isNotEmpty()) BodyText(stringResource(R.string.settings_import_profiles, preview.profileNames.joinToString()))
            if (preview.clashes.isNotEmpty()) BodyText(stringResource(R.string.settings_import_clashes, preview.clashes.joinToString()), tone = Tone.Warning)
            if (invalid > 0) BodyText(pluralStringResource(R.plurals.settings_import_invalid, invalid, invalid), tone = Tone.Danger)
            val extensions = preview.bundle.extensions.size
            if (extensions > 0) BodyText(pluralStringResource(R.plurals.settings_import_extensions, extensions, extensions))
            if (preview.bundle.ignored.isNotEmpty()) BodyText(stringResource(R.string.settings_import_ignored, preview.bundle.ignored.joinToString()))
            BodyText(stringResource(R.string.settings_import_modes), tone = Tone.Neutral)
            KitButton(stringResource(R.string.settings_import_replace), { onConfirm(ImportMode.REPLACE) }, style = KitButtonStyle.Danger)
        }
    }
}

/** The question names the layer and the consequence, as a destructive confirm does (ux-rules U-CMP-04). */
@Composable
fun ResetAllDialog(layerName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    KitDialog(
        title = stringResource(R.string.settings_reset_all_question, layerName),
        onDismiss = onDismiss,
        confirm = KitAction(stringResource(R.string.settings_reset_all_confirm)) { onConfirm(); onDismiss() },
        dismiss = KitAction(stringResource(R.string.action_cancel), onDismiss),
        tone = Tone.Danger,
    ) {
        BodyText(stringResource(R.string.settings_reset_all_body, layerName))
    }
}
