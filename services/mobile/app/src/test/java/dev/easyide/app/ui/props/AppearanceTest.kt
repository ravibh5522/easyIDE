package dev.easyide.app.ui.props

import dev.easyide.app.data.settings.AppearanceSettingsSchema
import dev.easyide.app.ui.foundation.WidthClass
import dev.easyide.app.data.settings.Setting
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {

    @Test fun `accent accepts the keywords and every hex spelling and drops alpha`() {
        assertEquals(AccentChoice.Theme, AccentChoice.parse("theme"))
        assertEquals(AccentChoice.Wallpaper, AccentChoice.parse(" wallpaper "))
        assertEquals(AccentChoice.Custom(0xFF8A3D), AccentChoice.parse("#FF8A3D"))
        assertEquals(AccentChoice.Custom(0xFF8A3D), AccentChoice.parse("#ff8a3d80"))
        assertEquals(AccentChoice.Custom(0xAABBCC), AccentChoice.parse("#abc"))
        assertEquals(AccentChoice.Custom(0xAABBCC), AccentChoice.parse("#abcd"))
    }

    @Test fun `accent rejects what is not a colour`() {
        listOf("", "orange", "FF8A3D", "#12", "#12345", "#GGGGGG", "#1234567").forEach { assertNull(it, AccentChoice.parse(it)) }
    }

    @Test fun `accent ids round trip`() {
        listOf(AccentChoice.Theme, AccentChoice.Wallpaper, AccentChoice.Custom(0x0A0B0C)).forEach {
            assertEquals(it, AccentChoice.parse(it.id))
        }
        assertEquals("#0A0B0C", AccentChoice.Custom(0x0A0B0C).id)
    }

    @Test fun `every choice has a distinct stable id`() {
        fun ids(vararg all: Iterable<String>) = all.forEach { assertEquals(it.toList(), it.toList().distinct()) }
        ids(Density.entries.map { it.id }, Corners.entries.map { it.id }, FontPairing.entries.map { it.id },
            ChromeContrast.entries.map { it.id }, Motif.entries.map { it.id }, HapticsLevel.entries.map { it.id },
            ReduceMotion.entries.map { it.id }, IconStyle.entries.map { it.id }, Handedness.entries.map { it.id })
        assertEquals(listOf("dense", "comfortable", "spacious"), Density.entries.map { it.id })
        assertEquals(listOf("auto", "dense", "comfortable", "spacious"), DensityPref.entries.map { it.id })
        assertEquals(listOf("geist", "monoChrome", "system"), FontPairing.entries.map { it.id })
    }

    @Test fun `the schema stores ids and reads them back, rejecting unknown values`() {
        val setting = AppearanceSettingsSchema.fontPairing
        FontPairing.entries.forEach { assertEquals(it, setting.decode(setting.encode(it))) }
        assertEquals(JsonPrimitive("monoChrome"), setting.encode(FontPairing.MONO_CHROME))
        assertNull(setting.decode(JsonPrimitive("MONO_CHROME")))
        assertNull(setting.decode(JsonPrimitive("serif")))
        assertEquals(listOf("geist", "monoChrome", "system"), setting.ids)
    }

    @Test fun `density decodes its ids and the legacy compact spelling, and rejects the rest`() {
        val setting = AppearanceSettingsSchema.density
        assertEquals(DensityPref.DENSE, setting.decode(JsonPrimitive("compact")))
        assertEquals(DensityPref.DENSE, setting.decode(JsonPrimitive("dense")))
        assertEquals(DensityPref.AUTO, setting.decode(JsonPrimitive("auto")))
        assertEquals(DensityPref.COMFORTABLE, setting.decode(JsonPrimitive("comfortable")))
        assertEquals(DensityPref.SPACIOUS, setting.decode(JsonPrimitive("spacious")))
        assertNull(setting.decode(JsonPrimitive("tiny")))
        assertNull(setting.decode(JsonPrimitive("DENSE")))
        DensityPref.entries.forEach { assertEquals(it, setting.decode(setting.encode(it))) }
        assertEquals(JsonPrimitive("dense"), setting.encode(DensityPref.DENSE))
    }

    @Test fun `auto density resolves to no fixed step and a fixed pref to its own`() {
        assertNull(DensityPref.AUTO.density)
        assertEquals(Density.SPACIOUS, DensityPref.SPACIOUS.density)
        assertEquals(Density.COMFORTABLE, Density.forWidth(WidthClass.COMPACT))
        assertEquals(Density.DENSE, Density.forWidth(WidthClass.MEDIUM))
    }

    @Test fun `the accent and scale settings validate their input`() {
        val accent = AppearanceSettingsSchema.accent
        assertEquals("#FF8A3D", accent.decode(JsonPrimitive("#FF8A3D")))
        assertNull(accent.decode(JsonPrimitive("orange")))
        val scale = AppearanceSettingsSchema.uiScale
        assertEquals(115, scale.decode(JsonPrimitive(115)))
        assertNull(scale.decode(JsonPrimitive(60)))
        assertNull(scale.decode(JsonPrimitive(200)))
    }

    @Test fun `the schema defaults are the Appearance defaults`() {
        val a = Appearance.DEFAULT
        assertEquals(a.density, AppearanceSettingsSchema.density.default.density)
        assertNull(a.density)
        assertEquals(a.corners, AppearanceSettingsSchema.corners.default)
        assertEquals(a.uiScalePercent, AppearanceSettingsSchema.uiScale.default)
        assertEquals(a.cursorBlink, AppearanceSettingsSchema.cursorBlink.default)
        assertEquals(a.reduceMotion, AppearanceSettingsSchema.reduceMotion.default)
        assertEquals(a.accent.id, AppearanceSettingsSchema.accent.default)
        assertTrue(AppearanceSettingsSchema.all.all { it.key.startsWith("appearance.") })
        assertEquals(12, AppearanceSettingsSchema.all.size)
    }

    @Test fun `motion honours reduce settings and the screen reader`() {
        assertTrue(Motion.of(Appearance(), systemReduces = false).cursorBlinks)
        assertEquals(0, Motion.of(Appearance(reduceMotion = ReduceMotion.ON), systemReduces = false).standardMs)
        assertEquals(0, Motion.of(Appearance(), systemReduces = true).paneMs)
        assertEquals(140, Motion.of(Appearance(reduceMotion = ReduceMotion.OFF), systemReduces = true).standardMs)
        assertEquals(false, Motion.of(Appearance(), systemReduces = false, screenReaderOn = true).cursorBlinks)
        assertEquals(false, Motion.of(Appearance(cursorBlink = false), systemReduces = false).cursorBlinks)
        assertTrue(Motion.PANE_MS <= Motion.CEILING_MS)
    }
}
