package dev.easyide.app.ui.kit.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag

private val STYLES = listOf(
    KitButtonStyle.Primary to R.string.gallery_style_primary,
    KitButtonStyle.Secondary to R.string.gallery_style_secondary,
    KitButtonStyle.Ghost to R.string.gallery_style_ghost,
    KitButtonStyle.Danger to R.string.gallery_style_danger,
)

/** KitButton and KitIconButton: every style enabled, disabled, loading and with an icon; the large size; a long label. */
@Composable
fun ButtonsSection() {
    val space = Kit.space
    KitSection(stringResource(R.string.gallery_buttons_title)) {
        for ((style, label) in STYLES) {
            Sample(pairLabel(label, R.string.gallery_state_enabled)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(space.s), verticalArrangement = Arrangement.spacedBy(space.s)) {
                    KitButton(stringResource(R.string.gallery_action_save), {}, style = style)
                    KitButton(stringResource(R.string.gallery_action_add), {}, style = style, icon = Icons.Filled.Add)
                    KitButton(stringResource(R.string.gallery_state_disabled), {}, style = style, enabled = false)
                    KitButton(stringResource(R.string.gallery_state_loading), {}, style = style, loading = true)
                }
            }
        }
        Sample(stringResource(R.string.gallery_button_large)) {
            KitButton(stringResource(R.string.gallery_action_save), {}, large = true)
        }
        Sample(stringResource(R.string.gallery_long_text)) {
            KitButton(stringResource(R.string.gallery_long_label), {}, style = KitButtonStyle.Secondary, icon = Icons.Filled.Check)
        }
    }
    KitSection(stringResource(R.string.gallery_icon_buttons_title), description = stringResource(R.string.gallery_icon_buttons_note)) {
        Sample(stringResource(R.string.gallery_state_enabled)) { IconRow(enabled = true) }
        Sample(stringResource(R.string.gallery_state_disabled)) { IconRow(enabled = false) }
    }
}

@Composable
private fun IconRow(enabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
        for ((tone, _) in TONE_LABELS) {
            KitIconButton(Icons.Filled.Delete, stringResource(R.string.gallery_icon_delete), {}, tone = tone, enabled = enabled)
        }
    }
}

/** KitTag in every tone: static, selected, tappable, with an icon, and a long one that must ellipsize. */
@Composable
fun TagsSection() {
    val space = Kit.space
    KitSection(stringResource(R.string.gallery_tags_title), description = stringResource(R.string.gallery_tags_note)) {
        for ((tone, label) in TONE_LABELS) {
            Sample(stringResource(label)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(space.s), verticalArrangement = Arrangement.spacedBy(space.s)) {
                    KitTag(stringResource(R.string.gallery_tag_text), tone = tone)
                    KitTag(stringResource(R.string.gallery_tag_text), tone = tone, selected = true, onClick = {})
                    KitTag(stringResource(R.string.gallery_tag_text), tone = tone, onClick = {})
                    KitTag(stringResource(R.string.gallery_tag_text), tone = tone, icon = Icons.Filled.Check, onClick = {})
                }
            }
        }
        Sample(stringResource(R.string.gallery_long_text)) { KitTag(stringResource(R.string.gallery_long_label), onClick = {}) }
    }
}
