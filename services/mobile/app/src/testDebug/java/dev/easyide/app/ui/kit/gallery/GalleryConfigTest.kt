package dev.easyide.app.ui.kit.gallery

import dev.easyide.app.ui.devtools.DevRoutes
import dev.easyide.app.ui.props.AccentChoice
import dev.easyide.app.ui.props.Appearance
import dev.easyide.app.ui.props.Corners
import dev.easyide.app.ui.props.Density
import dev.easyide.app.ui.props.Motif
import dev.easyide.app.ui.props.ReduceMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryConfigTest {

    @Test fun `untouched controls describe the default appearance`() {
        assertEquals(Appearance.DEFAULT, GalleryConfig().appearance())
    }

    @Test fun `each control reaches its appearance property`() {
        val config = GalleryConfig(
            density = Density.SPACIOUS,
            corners = Corners.ROUND,
            accent = AccentChoice.Custom(0x3B82F6),
            motif = Motif.FULL,
            reduceMotion = true,
        )
        val appearance = config.appearance()
        assertEquals(Density.SPACIOUS, appearance.density)
        assertEquals(Corners.ROUND, appearance.corners)
        assertEquals(AccentChoice.Custom(0x3B82F6), appearance.accent)
        assertEquals(Motif.FULL, appearance.motif)
        assertEquals(ReduceMotion.ON, appearance.reduceMotion)
    }

    @Test fun `reduce motion off defers to the system instead of forcing animation`() {
        assertEquals(ReduceMotion.SYSTEM, GalleryConfig(reduceMotion = false).appearance().reduceMotion)
    }

    @Test fun `font scale is a system size and never leaks into the ui scale property`() {
        assertEquals(Appearance.UI_SCALE_DEFAULT, GalleryConfig(fontScale = 2f).appearance().uiScalePercent)
    }

    @Test fun `every option table lists each value once and starts from the default`() {
        val tables = listOf(
            GalleryOptions.DENSITIES, GalleryOptions.CORNERS, GalleryOptions.MODES, GalleryOptions.MOTIFS, GalleryOptions.ACCENTS,
        )
        for (table in tables) assertEquals(table.size, table.map { it.value }.distinct().size)
        val start = GalleryConfig()
        assertTrue(GalleryOptions.DENSITIES.any { it.value == start.density })
        assertTrue(GalleryOptions.CORNERS.any { it.value == start.corners })
        assertTrue(GalleryOptions.MODES.any { it.value == start.mode })
        assertTrue(GalleryOptions.MOTIFS.any { it.value == start.motif })
        assertEquals(start.accent, GalleryOptions.ACCENTS.first().value)
        assertEquals(start.fontScale, GalleryOptions.FONT_SCALES.first(), 0f)
    }

    @Test fun `font scales cover the default, the common large step and the maximum`() {
        assertEquals(listOf(1f, 1.3f, 2f), GalleryOptions.FONT_SCALES)
    }

    @Test fun `accent swatches survive the settings codec`() {
        for (option in GalleryOptions.ACCENTS) assertEquals(option.value, AccentChoice.parse(option.value.id))
    }

    @Test fun `the gallery route exists in debug builds`() {
        assertTrue(DevRoutes.KIT_GALLERY.isNotEmpty())
    }
}
