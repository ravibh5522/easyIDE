package dev.easyide.app.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonDiagnosticsTest {

    private val schema = SettingsRegistry(SettingsSchema.all).also {
        it.setContributions(
            listOf(ConfigurationContribution("acme", json("{\"properties\": {\"acme.old\": {\"type\": \"boolean\", \"deprecationMessage\": \"use acme.new\"}}}"), null)),
        )
    }.state.value

    private fun codes(text: String, layer: LayerId) = SettingsJsonDiagnostics.check(text, layer, schema).map { it.code to it.key }

    @Test
    fun oneFixturePerRow() {
        assertEquals(DiagnosticCode.PARSE_ERROR, codes("{\"a\": ", LayerId.USER).single().first)
        assertEquals(DiagnosticCode.NOT_AN_OBJECT, codes("[]", LayerId.USER).single().first)
        assertEquals(listOf(DiagnosticCode.INVALID_VALUE to "editor.fontSize"), codes("{\"editor.fontSize\": 500}", LayerId.USER))
        assertEquals(listOf(DiagnosticCode.UNKNOWN_KEY to "nope"), codes("{\"nope\": 1}", LayerId.USER))
        assertEquals(listOf(DiagnosticCode.NOT_IN_THIS_LAYER to "terminal.fontSize"), codes("{\"terminal.fontSize\": 12}", LayerId.PROJECT))
        assertEquals(listOf(DiagnosticCode.NOT_LANGUAGE_OVERRIDABLE to "terminal.fontSize"), codes("{\"[py]\": {\"terminal.fontSize\": 12}}", LayerId.USER))
        assertEquals(DiagnosticCode.PROTECTED_KEY to "profiles.active", codes("{\"profiles.active\": \"x\"}", LayerId.PROJECT).first())
        assertEquals(listOf(DiagnosticCode.DEPRECATED to "acme.old"), codes("{\"acme.old\": true}", LayerId.USER))
        assertTrue(codes("{\"lsp.servers\": {\"s\": {\"command\": [\"x\"]}}}", LayerId.PROJECT).contains(DiagnosticCode.NEEDS_TRUST to "lsp.servers"))
        assertFalse(codes("{\"lsp.servers\": {\"s\": {\"command\": [\"x\"]}}}", LayerId.USER).any { it.first == DiagnosticCode.NEEDS_TRUST })
        assertEquals(emptyList<Any>(), codes("{\"editor.fontSize\": 14, \"[py]\": {\"editor.fontSize\": 15}}", LayerId.PROJECT))
    }

    @Test
    fun onlyParseErrorsBlockSaving() {
        assertTrue(SettingsJsonDiagnostics.blocksSave(SettingsJsonDiagnostics.check("{", LayerId.USER, schema)))
        assertFalse(SettingsJsonDiagnostics.blocksSave(SettingsJsonDiagnostics.check("{\"editor.fontSize\": 500}", LayerId.USER, schema)))
    }

    @Test
    fun offsetsPointAtTheKey() {
        val text = "{\n  \"a\": 1,\n  \"editor.fontSize\": 1\n}"
        val d = SettingsJsonDiagnostics.check(text, LayerId.USER, schema).first { it.code == DiagnosticCode.INVALID_VALUE }
        assertEquals(3, Jsonc.lineOf(text, d.offset!!))
    }
}
