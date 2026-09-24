package dev.easyide.app.ui.kit.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.ui.icons.CORE_GLYPHS
import dev.easyide.app.ui.icons.EiGlyph
import dev.easyide.app.ui.icons.GIT_GLYPHS
import dev.easyide.app.ui.icons.SYSTEM_GLYPHS
import dev.easyide.app.ui.icons.WORK_GLYPHS
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.theme.EasyIdeFonts

private val SHEET_ICON = 32.dp
private val CELL_WIDTH = 112.dp
private val SIZES = listOf(16.dp, 20.dp, 24.dp, 32.dp)

/** The custom icon set for the owner to inspect: each group at sheet size, then one glyph per tone and per size. */
@Composable
fun IconsSection() {
    KitSection(stringResource(R.string.gallery_icons_title), description = stringResource(R.string.gallery_icons_note)) {
        Sample(stringResource(R.string.gallery_icons_core)) { GlyphGrid(CORE_GLYPHS) }
        Sample(stringResource(R.string.gallery_icons_work)) { GlyphGrid(WORK_GLYPHS) }
        Sample(stringResource(R.string.gallery_icons_git)) { GlyphGrid(GIT_GLYPHS) }
        Sample(stringResource(R.string.gallery_icons_system)) { GlyphGrid(SYSTEM_GLYPHS) }
        Sample(stringResource(R.string.gallery_icons_tones)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Kit.space.l)) {
                for ((tone, _) in TONE_LABELS) GlyphImage(CORE_GLYPHS[0], SHEET_ICON, tone.content(Kit.colors))
            }
        }
        Sample(stringResource(R.string.gallery_icons_sizes)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Kit.space.l), verticalAlignment = Alignment.CenterVertically) {
                for (size in SIZES) GlyphImage(GIT_GLYPHS[0], size, Kit.colors.plainText)
            }
        }
    }
}

@Composable
private fun GlyphGrid(glyphs: List<EiGlyph>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Kit.space.s), verticalArrangement = Arrangement.spacedBy(Kit.space.m)) {
        for (glyph in glyphs) {
            Column(Modifier.width(CELL_WIDTH), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
                GlyphImage(glyph, SHEET_ICON, Kit.colors.plainText)
                BasicText(glyph.name, style = Kit.type.labelSmall.copy(fontFamily = EasyIdeFonts.mono, color = Kit.colors.textMuted))
            }
        }
    }
}

@Composable
private fun GlyphImage(glyph: EiGlyph, size: Dp, tint: Color) {
    Image(glyph.vector, null, Modifier.size(size), colorFilter = ColorFilter.tint(tint))
}
