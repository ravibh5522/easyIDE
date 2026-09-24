package dev.easyide.app.extensions

import dev.easyide.extensions.action.LogEntry
import dev.easyide.extensions.action.LogLevel
import dev.easyide.extensions.manifest.ExtensionDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtensionLogRingTest {

    @Test fun `the log ring keeps the newest entries`() {
        val ring = ExtensionLogRing(capacity = 2, clock = { 1L }, mirror = {})
        repeat(3) { ring.append(LogEntry(null, LogLevel.INFO, "m$it")) }
        assertEquals(listOf("m1", "m2"), ring.entries.value.map { it.entry.message })
        ring.clear()
        assertEquals(emptyList<Any>(), ring.entries.value)
    }

    @Test fun `configuration contributions rebuild the manifest shape for the settings registry`() {
        val d: ExtensionDescriptor = ExtFixtures.descriptor(ExtFixtures.manifest("""
            "contributes": {
              "configuration": { "title": "Demo", "properties": { "demo.path": { "type": "string", "default": "python3" } } },
              "configurationDefaults": { "editor.tabSize": 2, "[python]": { "editor.tabSize": 4 } }
            }
        """))
        val c = ConfigurationContributions.of(d)
        assertEquals("acme.demo", c.owner)
        assertEquals("""[{"title":"Demo","properties":{"demo.path":{"type":"string","default":"python3"}}}]""", c.configuration.toString())
        assertEquals("""{"editor.tabSize":2,"[python]":{"editor.tabSize":4}}""", c.configurationDefaults.toString())
        assertNull(ConfigurationContributions.of(ExtFixtures.descriptor(ExtFixtures.manifest(""" "categories": ["Other"] """))).configuration)
    }
}
