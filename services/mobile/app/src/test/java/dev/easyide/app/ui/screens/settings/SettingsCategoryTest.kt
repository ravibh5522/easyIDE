package dev.easyide.app.ui.screens.settings

import dev.easyide.app.data.settings.ConfigurationContribution
import dev.easyide.app.data.settings.Setting
import dev.easyide.app.data.settings.SettingsRegistry
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.data.settings.json
import dev.easyide.app.ui.props.ShellSettingsSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCategoryTest {

    private val schema = SettingsSchema.all + ShellSettingsSchema.all

    @Test fun `ids are distinct and are the document path`() {
        val ids = SettingsCategory.entries.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(
            listOf("appearance", "editor", "terminal", "files", "git", "sandbox", "keyboard", "language-servers", "extensions", "layout", "diagnostics", "advanced"),
            ids,
        )
        SettingsCategory.entries.forEach { assertEquals(it, SettingsCategory.ofId(it.id)) }
        assertNull(SettingsCategory.ofId(SettingsCategory.SEARCH_ID))
        assertNull(SettingsCategory.ofId(null))
    }

    @Test fun `every declared setting lands on a page that lists schema rows`() {
        val rowPages = setOf(
            SettingsCategory.APPEARANCE, SettingsCategory.EDITOR, SettingsCategory.TERMINAL, SettingsCategory.FILES, SettingsCategory.GIT,
            SettingsCategory.LANGUAGE_SERVERS, SettingsCategory.EXTENSIONS, SettingsCategory.LAYOUT,
        )
        schema.forEach { assertTrue("${it.key} -> ${SettingsCategory.of(it)}", SettingsCategory.of(it) in rowPages) }
    }

    @Test fun `explorer and workspace keys read as files, shell keys as layout`() {
        fun page(key: String) = SettingsCategory.of(schema.first { it.key == key })
        assertEquals(SettingsCategory.FILES, page("explorer.hideHiddenFiles"))
        assertEquals(SettingsCategory.FILES, page("workspace.restoreOpenTabs"))
        assertEquals(SettingsCategory.EDITOR, page("editor.fontSize"))
        assertEquals(SettingsCategory.LAYOUT, page("shell.navigation.order"))
        assertEquals(SettingsCategory.APPEARANCE, page("appearance.accent"))
        assertEquals(SettingsCategory.LANGUAGE_SERVERS, page("lsp.enabled"))
    }

    @Test fun `contributed settings go to extensions`() {
        val registry = SettingsRegistry(SettingsSchema.all)
        registry.setContributions(
            listOf(
                ConfigurationContribution(
                    "acme.tool", json("""{"title": "Acme", "properties": {"acme.flag": {"type": "boolean", "default": true}}}"""), null,
                ),
            ),
        )
        val contributed = registry.state.value.settings.filterIsInstance<Setting.Contributed>()
        assertTrue(contributed.isNotEmpty())
        contributed.forEach { assertEquals(SettingsCategory.EXTENSIONS, SettingsCategory.of(it)) }
    }

    @Test fun `device-wide pages are not layered`() {
        assertEquals(
            setOf(SettingsCategory.KEYBOARD, SettingsCategory.SANDBOX, SettingsCategory.DIAGNOSTICS, SettingsCategory.ADVANCED),
            SettingsCategory.entries.filterNot { it.layered }.toSet(),
        )
    }
}
