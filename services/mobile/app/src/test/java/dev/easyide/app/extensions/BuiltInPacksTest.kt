package dev.easyide.app.extensions

import dev.easyide.app.extensions.adapters.ColorThemeFile
import dev.easyide.app.extensions.adapters.ContributedThemes
import dev.easyide.app.extensions.adapters.SnippetFile
import dev.easyide.app.ui.theme.SyntaxRole
import dev.easyide.app.ui.theme.VsCodeThemeMapper
import dev.easyide.extensions.contrib.UiTheme
import dev.easyide.extensions.contrib.Owner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The APK's built-in packs go through the same reader and parser as any install; they must load clean. */
class BuiltInPacksTest {

    private fun load(id: String) = BuiltInPackFixtures.load(id)

    @Test fun `every built-in pack validates without errors or warnings`() {
        val ids = BuiltInPackFixtures.ids()
        assertEquals(
            listOf(
                "easyide.core-snippets", "easyide.cpp", "easyide.file-icons", "easyide.git-commands", "easyide.git-extras", "easyide.go",
                "easyide.key-rows", "easyide.markdown", "easyide.material-icons", "easyide.project-tasks", "easyide.python", "easyide.rust",
                "easyide.shell", "easyide.tablet-toolbar", "easyide.themes", "easyide.toggles", "easyide.typescript",
                "easyide.web", "easyide.yaml",
            ),
            ids,
        )
        ids.forEach { id -> assertEquals("$id warnings", emptyList<Any>(), load(id).warnings) }
    }

    @Test fun `git commands declare exactly what their actions need`() {
        val d = load("easyide.git-commands").descriptor
        assertEquals(setOf("sandbox.exec"), d.capabilities.items.map { it.id }.toSet())
        assertEquals(d.contributes.commands.map { it.command }.toSet(), d.actions.keys)
        assertTrue(d.contributes.menus.any { it.menuId == "explorer/context" })
        assertTrue(d.contributes.keybindings.isNotEmpty())
    }

    @Test fun `the theme pack's themes load clean, with every colour key mapped`() {
        val d = load("easyide.themes").descriptor
        assertEquals(setOf("Themes"), d.categories.toSet())
        assertEquals(listOf(UiTheme.DARK, UiTheme.LIGHT), d.contributes.themes.map { it.uiTheme })
        d.contributes.themes.forEach { t ->
            val read = { path: String -> File(path).readText() }
            val parsed = ColorThemeFile.load(t.file, read) { fail("${t.label}: $it") }!!
            val mapped = VsCodeThemeMapper.map(parsed, ContributedThemes.baseTokensFor(t.uiTheme))
            assertEquals(t.label, emptyList<String>(), mapped.unmappedKeys + mapped.invalidEntries)
            assertEquals(t.label, t.uiTheme == UiTheme.DARK, mapped.tokens.isDark)
            // Every syntax role is the theme's own, not the base palette's.
            val base = ContributedThemes.baseTokensFor(t.uiTheme).syntax
            SyntaxRole.entries.forEach { role -> assertNotEquals("${t.label} $role", base[role], mapped.tokens.syntax[role]) }
        }
    }

    @Test fun `every contributed snippet file parses`() {
        val d = load("easyide.core-snippets").descriptor
        assertTrue(d.contributes.keyRows.size == 2)
        d.contributes.snippets.forEach { s ->
            val parsed = SnippetFile.parse(File(s.file.hostPath).readText(), s.language, Owner.BuiltIn)
            assertTrue("${s.file.path} has snippets", !parsed.isNullOrEmpty())
        }
    }
}
