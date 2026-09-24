package dev.easyide.app.ui.kit.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.KitToggle
import dev.easyide.app.ui.kit.KitTwoColumnRow
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.kit.Twistie
import dev.easyide.app.ui.props.Density
import dev.easyide.app.ui.theme.EasyIdeTheme

/** [content] rendered twice, side by side, under an explicit Dense and an explicit Comfortable theme. */
@Composable
private fun DensityPair(config: GalleryConfig, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Kit.space.l), horizontalArrangement = Arrangement.spacedBy(Kit.space.l)) {
        for ((density, label) in DENSITY_PAIR) {
            Column(Modifier.weight(1f)) {
                EasyIdeTheme(themeMode = config.mode, appearance = config.appearance().copy(density = density)) {
                    Column(Modifier.fillMaxWidth().background(Kit.colors.panel)) {
                        BasicText(stringResource(label), Modifier.padding(Kit.space.s), style = Kit.text.caption.copy(color = Kit.colors.textMuted))
                        content()
                    }
                }
            }
        }
    }
}

private val DENSITY_PAIR = listOf(
    Density.DENSE to R.string.gallery_anatomy_dense,
    Density.COMFORTABLE to R.string.gallery_anatomy_comfortable,
)

/**
 * The anatomy of density.md 1 on real kit rows: a three-level tree, a list with inline
 * descriptions and badges, section headers with counts, two-column settings rows.
 */
@Composable
fun AnatomySection(config: GalleryConfig) {
    GalleryBlock(stringResource(R.string.gallery_anatomy_title)) {
        BasicText(stringResource(R.string.gallery_anatomy_note), style = Kit.text.caption.copy(color = Kit.colors.textMuted))
    }
    DensityPair(config) { AnatomyBody() }
}

@Composable
private fun AnatomyBody() {
    val colors = Kit.colors
    var wrap by remember { mutableStateOf(true) }
    val folder: @Composable () -> Unit = { Icon(Icons.Filled.Folder, null, Modifier.size(Kit.control.rowIcon), tint = colors.textMuted) }
    val file: @Composable () -> Unit = { Icon(Icons.Filled.Description, null, Modifier.size(Kit.control.rowIcon), tint = colors.textMuted) }
    val modified: @Composable () -> Unit = { KitTag(stringResource(R.string.gallery_badge_modified), tone = Tone.Warning) }
    KitSection(stringResource(R.string.gallery_tree_title), flat = true, collapsible = true, count = 3) {
        KitRow(stringResource(R.string.gallery_tree_root), leading = folder, twistie = Twistie.Expanded, onClick = {})
        KitRow(stringResource(R.string.gallery_tree_src), leading = folder, twistie = Twistie.Expanded, level = 1, onClick = {})
        KitRow(stringResource(R.string.gallery_tree_main), leading = folder, twistie = Twistie.Collapsed, level = 2, onClick = {})
        KitRow(stringResource(R.string.gallery_tree_kit), leading = file, twistie = Twistie.Leaf, level = 2, trailing = modified, selected = true, onClick = {})
        KitRow(stringResource(R.string.gallery_tree_row), leading = file, twistie = Twistie.Leaf, level = 2, subtitle = stringResource(R.string.gallery_list_desc_path), onClick = {})
        KitRow(stringResource(R.string.gallery_tree_test), leading = folder, twistie = Twistie.Collapsed, level = 1, onClick = {})
        KitRow(stringResource(R.string.gallery_tree_readme), leading = file, twistie = Twistie.Leaf, onClick = {})
    }
    KitSection(
        stringResource(R.string.gallery_changes_title), flat = true, count = 2,
        actions = { KitIconButton(Icons.Filled.Add, stringResource(R.string.gallery_action_add), {}) },
    ) {
        KitRow(stringResource(R.string.gallery_tree_kit), leading = file, subtitle = stringResource(R.string.gallery_list_desc_long), trailing = modified, onClick = {})
        KitRow(
            stringResource(R.string.gallery_list_title_long), leading = file, subtitle = stringResource(R.string.gallery_list_desc_long),
            trailing = { KitTag(stringResource(R.string.gallery_badge_new), tone = Tone.Success) }, onClick = {},
        )
    }
    KitSection(stringResource(R.string.gallery_staged_title), flat = true, collapsible = true, startExpanded = false, count = 0) {
        KitRow(stringResource(R.string.gallery_tree_readme))
    }
    KitSection(null, flat = true) {
        KitRow(
            stringResource(R.string.gallery_ext_name), subtitle = stringResource(R.string.gallery_ext_desc), secondLine = true,
            trailing = { KitTag(stringResource(R.string.gallery_ext_version)) },
        )
        KitRow(stringResource(R.string.gallery_ext_agents), subtitle = stringResource(R.string.gallery_ext_agents_desc), secondLine = true, selected = true)
    }
    KitSection(stringResource(R.string.gallery_setting_home), flat = true) {
        KitTwoColumnRow(stringResource(R.string.gallery_setting_wrap), description = stringResource(R.string.gallery_setting_wrap_desc)) {
            KitToggle(wrap, { wrap = it })
        }
        KitTwoColumnRow(stringResource(R.string.gallery_setting_home), description = stringResource(R.string.gallery_setting_home_desc)) {
            BasicText(stringResource(R.string.gallery_sample_path), style = Kit.text.monoSmall.copy(color = colors.textMuted), maxLines = 1)
        }
    }
}

/** Every role of `Kit.text`, its name and size, at Dense and Comfortable. */
@Composable
fun TypeSection(config: GalleryConfig) {
    GalleryBlock(stringResource(R.string.gallery_type_title)) {
        BasicText(stringResource(R.string.gallery_type_note), style = Kit.text.caption.copy(color = Kit.colors.textMuted))
    }
    DensityPair(config) {
        val text = Kit.text
        val roles = listOf(
            R.string.gallery_role_display to text.display, R.string.gallery_role_heading to text.heading,
            R.string.gallery_role_title to text.title, R.string.gallery_role_body to text.body,
            R.string.gallery_role_caption to text.caption, R.string.gallery_role_label to text.label,
            R.string.gallery_role_mono to text.mono, R.string.gallery_role_mono_small to text.monoSmall,
        )
        Column(Modifier.padding(Kit.space.s), verticalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
            for ((name, style) in roles) {
                val caption = stringResource(R.string.gallery_type_role, stringResource(name), style.fontSize.value.toInt())
                BasicText(caption, style = Kit.text.monoSmall.copy(color = Kit.colors.textMuted))
                Box { BasicText(stringResource(R.string.gallery_type_sample), style = style.copy(color = Kit.colors.plainText), maxLines = 1) }
            }
        }
    }
}
