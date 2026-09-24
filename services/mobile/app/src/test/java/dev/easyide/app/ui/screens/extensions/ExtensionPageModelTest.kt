package dev.easyide.app.ui.screens.extensions

import dev.easyide.app.ui.shell.DocumentUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionPageModelTest {

    private fun line(ref: String) = InspectorLine(ref, "/contributes/x/0", hiddenBy = null, conflicts = emptyList())

    @Test fun `capability lines sort by id and split granted from declared-only`() {
        val lines = capabilityLines(setOf("sandbox.exec", "clipboard", "network(pypi.org)"), setOf("clipboard", "network(pypi.org)"), builtIn = false)
        assertEquals(listOf("clipboard", "network(pypi.org)", "sandbox.exec"), lines.map { it.id })
        assertEquals(listOf(true, true, false), lines.map { it.granted })
        assertEquals(listOf(true, true, false), lines.map { it.revocable })
    }

    @Test fun `an approval for something no longer declared grants nothing and is not listed`() {
        val lines = capabilityLines(setOf("clipboard"), setOf("clipboard", "secrets.read"), builtIn = false)
        assertEquals(listOf("clipboard"), lines.map { it.id })
    }

    @Test fun `built-ins are granted what they declare and nothing can be revoked`() {
        val lines = capabilityLines(setOf("sandbox.exec"), emptySet(), builtIn = true)
        assertTrue(lines.single().granted)
        assertFalse(lines.single().revocable)
    }

    @Test fun `no declared capabilities means no lines`() {
        assertEquals(emptyList<CapabilityLine>(), capabilityLines(emptySet(), setOf("clipboard"), builtIn = false))
    }

    @Test fun `contribution refs map to the group a person recognises`() {
        assertEquals(ContributionGroup.Languages, groupOf("grammar:source.py"))
        assertEquals(ContributionGroup.Servers, groupOf("server:acme.tool/pyright"))
        assertEquals(ContributionGroup.Commands, groupOf("menu:editor/title:acme.run"))
        assertEquals(ContributionGroup.Views, groupOf("view:acme.venvs"))
        assertEquals(ContributionGroup.Appearance, groupOf("iconTheme:acme.icons"))
        assertEquals(ContributionGroup.Other, groupOf("configuration:acme.setting"))
        assertNull(groupOf("hologram:x"))
    }

    @Test fun `grouping keeps group order, drops empty groups and files unknown kinds under other`() {
        val groups = groupContributions(listOf(line("view:a"), line("command:b"), line("hologram:c"), line("command:d")))
        assertEquals(listOf(ContributionGroup.Commands, ContributionGroup.Views, ContributionGroup.Other), groups.map { it.first })
        assertEquals(listOf("command:b", "command:d"), groups.first().second.map { it.ref })
    }

    @Test fun `the settings link is offered only for an extension that contributes configuration`() {
        assertTrue(contributesSettings(listOf(line("command:a"), line("configuration:acme.flag"))))
        assertFalse(contributesSettings(listOf(line("command:a"), line("configurationDefaults:x"))))
        assertFalse(contributesSettings(emptyList()))
    }

    @Test fun `the settings target is a valid shell document uri`() {
        val uri = DocumentUri.parse(settingsTarget("easyide.python"))
        assertNotNull(uri)
        assertEquals("easyide", uri!!.scheme)
        assertEquals("settings", uri.authority)
        assertEquals(listOf("extensions"), uri.segments)
        assertEquals("easyide.python", uri.fragment)
    }
}
