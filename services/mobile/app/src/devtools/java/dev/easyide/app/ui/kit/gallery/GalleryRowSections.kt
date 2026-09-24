package dev.easyide.app.ui.kit.gallery

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitGroup
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitToggle
import dev.easyide.app.ui.kit.Tone

/** KitSection and KitRow in every state: plain, supported, tappable, selected, disabled, mono, wrapping, with slots. */
@Composable
fun RowsSection() {
    val colors = Kit.colors
    var on by remember { mutableStateOf(true) }
    KitSection(stringResource(R.string.gallery_rows_title), description = stringResource(R.string.gallery_rows_note)) {
        KitRow(stringResource(R.string.gallery_row_default))
        KitRow(stringResource(R.string.gallery_row_support), subtitle = stringResource(R.string.gallery_row_support_text))
        KitRow(
            stringResource(R.string.gallery_row_tappable),
            subtitle = stringResource(R.string.gallery_row_tappable_text),
            leading = { Icon(Icons.Filled.Folder, null, tint = colors.textMuted) },
            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.textMuted) },
            onClick = {},
        )
        KitRow(stringResource(R.string.gallery_row_value), trailing = { MonoValue(stringResource(R.string.gallery_sample_version)) })
        KitRow(stringResource(R.string.gallery_row_selected), selected = true, onClick = {})
        KitRow(stringResource(R.string.gallery_row_disabled), subtitle = stringResource(R.string.gallery_row_disabled_text), enabled = false, onClick = {})
        KitRow(stringResource(R.string.gallery_sample_path), mono = true, subtitle = stringResource(R.string.gallery_row_mono_text))
        KitRow(stringResource(R.string.gallery_row_long_title), subtitle = stringResource(R.string.gallery_row_long_text))
        KitRow(stringResource(R.string.gallery_row_toggle), trailing = { KitToggle(on, { on = it }) })
        KitRow(
            stringResource(R.string.gallery_row_actions),
            trailing = {
                Row {
                    KitIconButton(Icons.Filled.Settings, stringResource(R.string.gallery_icon_settings), {})
                    KitIconButton(Icons.Filled.Delete, stringResource(R.string.gallery_icon_delete), {}, tone = Tone.Danger)
                }
            },
        )
    }
}

@Composable
private fun MonoValue(text: String) {
    BasicText(text, style = Kit.text.monoSmall.copy(color = Kit.colors.textMuted))
}

/** KitGroup in each tone, and a nested group, which flattens by rule instead of drawing a card in a card. */
@Composable
fun GroupsSection() {
    GalleryBlock(stringResource(R.string.gallery_groups_title)) {
        for ((tone, label) in TONE_LABELS) {
            KitGroup(tone = tone) { KitRow(stringResource(label), subtitle = stringResource(R.string.gallery_group_text)) }
        }
        KitGroup {
            KitRow(stringResource(R.string.gallery_group_outer))
            KitGroup { KitRow(stringResource(R.string.gallery_group_inner)) }
        }
    }
}
