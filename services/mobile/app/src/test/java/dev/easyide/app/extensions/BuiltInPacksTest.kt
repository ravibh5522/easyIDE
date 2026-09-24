package dev.easyide.app.extensions

import dev.easyide.app.extensions.adapters.SnippetFile
import dev.easyide.extensions.contrib.Owner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The APK's built-in packs go through the same reader and parser as any install; they must load clean. */
class BuiltInPacksTest {

    private fun load(id: String) = BuiltInPackFixtures.load(id)

    @Test fun `every built-in pack validates without errors or warnings`() {
        val ids = BuiltInPackFixtures.ids()
        assertEquals(
            listOf(
                "easyide.core-snippets", "easyide.git-commands", "easyide.git-extras", "easyide.key-rows",
                "easyide.project-tasks", "easyide.python", "easyide.tablet-toolbar", "easyide.toggles",
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

    @Test fun `every contributed snippet file parses`() {
        val d = load("easyide.core-snippets").descriptor
        assertTrue(d.contributes.keyRows.size == 2)
        d.contributes.snippets.forEach { s ->
            val parsed = SnippetFile.parse(File(s.file.hostPath).readText(), s.language, Owner.BuiltIn)
            assertTrue("${s.file.path} has snippets", !parsed.isNullOrEmpty())
        }
    }
}
