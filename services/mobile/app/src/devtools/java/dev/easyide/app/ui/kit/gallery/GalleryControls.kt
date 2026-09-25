package dev.easyide.app.ui.kit.gallery

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.KitChoice
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitToggle

/**
 * The live property controls, built from the kit itself so they follow the settings they change.
 * Every edit replaces [config] and re-themes the whole gallery at once.
 */
@Composable
fun GalleryControls(config: GalleryConfig, onConfig: (GalleryConfig) -> Unit) {
    KitSection(
        title = stringResource(R.string.gallery_controls_title),
        description = stringResource(R.string.gallery_controls_note),
    ) {
        Sample(stringResource(R.string.gallery_density)) {
            OptionChoice(GalleryOptions.DENSITIES, config.density) { onConfig(config.copy(density = it)) }
        }
        Sample(stringResource(R.string.gallery_corners)) {
            OptionChoice(GalleryOptions.CORNERS, config.corners) { onConfig(config.copy(corners = it)) }
        }
        Sample(stringResource(R.string.gallery_font_scale)) {
            val names = GalleryOptions.FONT_SCALES.associateWith { stringResource(R.string.gallery_font_scale_value, it) }
            KitChoice(GalleryOptions.FONT_SCALES, config.fontScale, { names.getValue(it) }, onSelect = { onConfig(config.copy(fontScale = it)) })
        }
        Sample(stringResource(R.string.gallery_accent)) {
            OptionChoice(GalleryOptions.ACCENTS, config.accent) { onConfig(config.copy(accent = it)) }
        }
        Sample(stringResource(R.string.gallery_theme_mode)) {
            OptionChoice(GalleryOptions.MODES, config.mode) { onConfig(config.copy(mode = it)) }
        }
        Sample(stringResource(R.string.gallery_motif)) {
            OptionChoice(GalleryOptions.MOTIFS, config.motif) { onConfig(config.copy(motif = it)) }
        }
        KitRow(
            title = stringResource(R.string.gallery_reduce_motion),
            subtitle = stringResource(R.string.gallery_reduce_motion_hint),
            trailing = { KitToggle(config.reduceMotion, { onConfig(config.copy(reduceMotion = it)) }) },
        )
        KitRow(
            title = stringResource(R.string.gallery_reset),
            onClick = { onConfig(GalleryConfig()) },
            id = "gallery-reset",
        )
    }
}

@Composable
private fun <T> OptionChoice(options: List<Option<T>>, selected: T, onSelect: (T) -> Unit) {
    val names = options.associate { it.value to stringResource(it.label) }
    KitChoice(options.map { it.value }, selected, { names.getValue(it) }, onSelect)
}
