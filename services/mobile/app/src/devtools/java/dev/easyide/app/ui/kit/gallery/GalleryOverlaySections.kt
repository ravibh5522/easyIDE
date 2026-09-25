package dev.easyide.app.ui.kit.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.ui.kit.CellFillBar
import dev.easyide.app.ui.kit.CursorBlock
import dev.easyide.app.ui.kit.CursorStyle
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitMenu
import dev.easyide.app.ui.kit.KitMenuItem
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.PromptGlyph
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.kit.cropCorners

/** Which dialog specimen is open; null is none. */
private enum class DialogDemo { Plain, Danger, Long }

/** KitDialog (plain, destructive, long content) and KitMenu (icons, a checked entry, a hint, disabled, danger). */
@Composable
fun OverlaysSection() {
    var dialog by remember { mutableStateOf<DialogDemo?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(true) }
    val close = { dialog = null }
    KitSection(stringResource(R.string.gallery_overlays_title), description = stringResource(R.string.gallery_overlays_note)) {
        Sample(stringResource(R.string.gallery_dialog_title)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Kit.space.s), verticalArrangement = Arrangement.spacedBy(Kit.space.s)) {
                KitButton(stringResource(R.string.gallery_dialog_plain), { dialog = DialogDemo.Plain }, style = KitButtonStyle.Secondary)
                KitButton(stringResource(R.string.gallery_dialog_danger), { dialog = DialogDemo.Danger }, style = KitButtonStyle.Secondary)
                KitButton(stringResource(R.string.gallery_dialog_long), { dialog = DialogDemo.Long }, style = KitButtonStyle.Secondary)
            }
        }
        Sample(stringResource(R.string.gallery_menu_title)) {
            Box {
                KitButton(stringResource(R.string.gallery_menu_open), { menuOpen = true }, style = KitButtonStyle.Secondary)
                KitMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    items = listOf(
                        KitMenuItem.Action(stringResource(R.string.gallery_action_add), {}, icon = Icons.Filled.Add, hint = stringResource(R.string.gallery_menu_hint)),
                        KitMenuItem.Action(stringResource(R.string.gallery_menu_checked), { checked = !checked }, checked = checked),
                        KitMenuItem.Action(stringResource(R.string.gallery_state_disabled), {}, enabled = false),
                        KitMenuItem.Divider,
                        KitMenuItem.Action(stringResource(R.string.gallery_icon_delete), {}, icon = Icons.Filled.Delete, danger = true),
                    ),
                )
            }
        }
    }
    val demo = dialog ?: return
    val danger = demo == DialogDemo.Danger
    KitDialog(
        title = stringResource(if (danger) R.string.gallery_dialog_danger_title else R.string.gallery_dialog_plain_title),
        onDismiss = close,
        confirm = KitAction(stringResource(if (danger) R.string.gallery_icon_delete else R.string.gallery_action_save), close),
        dismiss = KitAction(stringResource(R.string.gallery_action_cancel), close),
        tone = if (danger) Tone.Danger else Tone.Neutral,
    ) {
        BasicText(
            stringResource(if (demo == DialogDemo.Long) R.string.gallery_dialog_long_body else R.string.gallery_long_body),
            style = Kit.text.body.copy(color = Kit.colors.plainText),
        )
    }
}

/** The drawn motifs: the three cursor states, the prompt glyph, crop corners, and the cell-fill bar at three fractions. */
@Composable
fun MotifsSection() {
    KitSection(stringResource(R.string.gallery_motifs_title), description = stringResource(R.string.gallery_motifs_note)) {
        Sample(stringResource(R.string.gallery_cursor)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Kit.space.l), verticalAlignment = Alignment.CenterVertically) {
                CursorBlock(style = CursorStyle.Solid)
                CursorBlock(style = CursorStyle.Hollow)
                CursorBlock(style = CursorStyle.Blinking)
            }
        }
        Sample(stringResource(R.string.gallery_prompt_glyph)) { PromptGlyph() }
        for (tone in listOf(Tone.Accent, Tone.Danger)) {
            Sample(stringResource(TONE_LABELS.first { it.first == tone }.second)) {
                Box(Modifier.fillMaxWidth().height(CORNERS_BOX).cropCorners(tone).padding(Kit.space.m), contentAlignment = Alignment.CenterStart) {
                    BasicText(stringResource(R.string.gallery_crop_corners), style = Kit.text.body.copy(color = Kit.colors.plainText))
                }
            }
        }
        for (fraction in listOf(0f, 0.35f, 1f)) {
            Sample(stringResource(R.string.gallery_progress_fraction, (fraction * PERCENT).toInt())) { CellFillBar(fraction) }
        }
    }
}

private val CORNERS_BOX = 64.dp
