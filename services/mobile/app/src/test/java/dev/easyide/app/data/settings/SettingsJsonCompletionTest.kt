package dev.easyide.app.data.settings

import dev.easyide.app.data.settings.SettingsJsonCompletion.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonCompletionTest {

    private val schema = SchemaState.builtInOnly(SettingsSchema.all)

    /** [marked] holds `|` at the cursor. */
    private fun suggest(marked: String, kind: Kind = Kind.SETTINGS, commands: List<String> = emptyList()): Pair<String, List<JsonSuggestion>> {
        val cursor = marked.indexOf('|')
        val text = marked.removeRange(cursor, cursor + 1)
        return text to SettingsJsonCompletion.suggest(text, cursor, kind, schema, commands)
    }

    private fun applied(marked: String, label: String, kind: Kind = Kind.SETTINGS, commands: List<String> = emptyList()): String {
        val (text, list) = suggest(marked, kind, commands)
        return list.first { it.label == label }.applyTo(text).first
    }

    @Test fun `keys complete inside a quote and without one`() {
        val (_, list) = suggest("{\n  \"editor.font|\n}")
        assertTrue(list.map { it.label }.containsAll(listOf("editor.fontSize")))
        assertEquals("{\n  \"editor.fontSize\": \n}", applied("{\n  \"editor.font|\n}", "editor.fontSize"))
        assertEquals("{\"a\": 1, \"editor.fontSize\": }", applied("{\"a\": 1, edit|}", "editor.fontSize"))
        // An existing closing quote is reused, not doubled.
        assertEquals("{\"lsp.enabled\": }", applied("{\"lsp.en|\"}", "lsp.enabled"))
    }

    @Test fun `language blocks offer only language overridable keys`() {
        val labels = suggest("{\"[python]\": {\"edit|").second.map { it.label }
        assertTrue("editor.fontSize" in labels)
        val terminal = SettingsJsonCompletion.suggest("{\"[python]\": {\"terminal.font", 28, Kind.SETTINGS, schema)
        assertEquals(emptyList<JsonSuggestion>(), terminal)
        // Deeper objects (lsp.servers entries) get no key suggestions.
        assertEquals(emptyList<JsonSuggestion>(), suggest("{\"lsp.servers\": {\"x\": {\"|").second)
    }

    @Test fun `enum and boolean values complete for the key before the colon`() {
        assertEquals("{\"lsp.trace\": \"VERBOSE\"}", applied("{\"lsp.trace\": \"V|\"}", "VERBOSE"))
        assertEquals("{\"lsp.trace\": \"OFF\"", applied("{\"lsp.trace\": |", "OFF"))
        assertEquals("{\"lsp.enabled\": false", applied("{\"lsp.enabled\": f|", "false"))
        assertEquals(emptyList<JsonSuggestion>(), suggest("{\"editor.fontSize\": |").second)
    }

    @Test fun `keybindings complete command ids and nothing inside comments`() {
        val commands = listOf("workbench.action.files.save", "workbench.action.files.saveAll")
        assertEquals(
            "[{\"key\": \"ctrl+s\", \"command\": \"workbench.action.files.saveAll\"}]",
            applied("[{\"key\": \"ctrl+s\", \"command\": \"saveA|\"}]", "workbench.action.files.saveAll", Kind.KEYBINDINGS, commands),
        )
        assertEquals(emptyList<JsonSuggestion>(), suggest("[{\"key\": \"|", Kind.KEYBINDINGS, commands).second)
        assertEquals(emptyList<JsonSuggestion>(), suggest("{\n // \"editor.|\n}").second)
    }
}
