package dev.easyide.app.ui.screens.settings

import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.SettingsSnapshot
import dev.easyide.app.data.settings.SchemaState
import dev.easyide.app.data.settings.layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {

    private val all = SettingsSchema.all
    private val plain = SettingsSnapshot(SchemaState.builtInOnly(all), emptyList())

    /** The keys stand in for the resolved titles: string resources need a Context. */
    private val textOf: (dev.easyide.app.data.settings.Setting<*>) -> List<String> = { listOf(it.key) }

    private fun keys(query: String, snapshot: SettingsSnapshot = plain, hidden: Set<String> = emptySet()) =
        SettingsSearch.matching(all, SettingsFilter.parse(query), snapshot, LayerId.USER, hidden, textOf).map { it.key }

    @Test fun `filters are read out of the query and the rest is the text`() {
        val f = SettingsFilter.parse("font  @modified @lang:python @ext:acme.tool size")
        assertEquals("font size", f.text)
        assertTrue(f.modifiedOnly)
        assertEquals("python", f.language)
        assertEquals("acme.tool", f.extension)
        assertTrue(f.isActive)
        assertFalse(SettingsFilter.parse("   ").isActive)
        assertNull(SettingsFilter.parse("@lang:").language)
    }

    @Test fun `every word must match the key`() {
        assertEquals(listOf("editor.fontSize"), keys("editor fontsize"))
        assertTrue(keys("nothing-like-this").isEmpty())
        assertTrue("shell.navigation.order" in keys("navigation order"))
    }

    @Test fun `modified only keeps what the layer holds`() {
        val s = SettingsSnapshot(SchemaState.builtInOnly(all), listOf(layer(LayerId.USER, """{"editor.fontSize": 15}""")))
        assertEquals(listOf("editor.fontSize"), keys("@modified", s))
    }

    @Test fun `a language filter keeps only language-overridable keys`() {
        val matches = SettingsSearch.matching(all, SettingsFilter.parse("@lang:python"), plain, LayerId.USER, emptySet(), textOf)
        assertTrue(matches.isNotEmpty())
        assertTrue(matches.all { it.scope.languageOverridable })
    }

    @Test fun `hidden keys never match`() {
        assertFalse("workbench.colorTheme" in keys("colorTheme", hidden = setOf("workbench.colorTheme")))
        assertTrue("workbench.colorTheme" in keys("colorTheme"))
    }

    @Test fun `results are ranked by page order and keep declared order inside a page`() {
        val ranked = SettingsSearch.ranked(SettingsSearch.matching(all, SettingsFilter.parse("e"), plain, LayerId.USER, emptySet(), textOf))
        val pages = ranked.map { SettingsCategory.of(it).ordinal }
        assertEquals(pages.sorted(), pages)
        val editorKeys = ranked.filter { SettingsCategory.of(it) == SettingsCategory.EDITOR }.map { it.key }
        assertEquals(all.filter { it.key in editorKeys }.map { it.key }, editorKeys)
    }

    @Test fun `a page's rows form one built-in section with no title`() {
        val rows = all.filter { SettingsCategory.of(it) == SettingsCategory.GIT }
        val sections = SettingsSearch.sections(rows)
        assertEquals(1, sections.size)
        assertNull(sections.single().title)
        assertEquals(rows, sections.single().rows)
        assertTrue(SettingsSearch.sections(emptyList()).isEmpty())
    }
}
