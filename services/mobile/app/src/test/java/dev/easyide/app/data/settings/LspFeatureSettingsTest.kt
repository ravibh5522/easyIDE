package dev.easyide.app.data.settings

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LspFeatureSettingsTest {

    private val semantic = LspSettingsSchema.semanticHighlighting

    @Test
    fun semanticHighlightingAcceptsVsCodeSpellings() {
        assertEquals(SemanticHighlighting.ON, semantic.decode(JsonPrimitive(true)))
        assertEquals(SemanticHighlighting.OFF, semantic.decode(JsonPrimitive(false)))
        assertEquals(SemanticHighlighting.CONFIGURED_BY_THEME, semantic.decode(JsonPrimitive("configuredByTheme")))
        assertEquals(SemanticHighlighting.OFF, semantic.decode(JsonPrimitive("OFF")))
        assertNull(semantic.decode(JsonPrimitive("sometimes")))
        assertEquals(SemanticHighlighting.CONFIGURED_BY_THEME, semantic.default)
    }

    @Test
    fun featureTogglesResolvePerLanguage() {
        val snapshot = SettingsSnapshot(
            SchemaState.builtInOnly(SettingsSchema.all),
            listOf(layer(LayerId.USER, """{"[go]": {"editor.codeLens": false, "editor.semanticHighlighting.enabled": false}}""")),
        )
        assertEquals(false, snapshot.get(LspSettingsSchema.codeLens, "go"))
        assertEquals(true, snapshot.get(LspSettingsSchema.codeLens, "python"))
        assertEquals(SemanticHighlighting.OFF, snapshot.get(semantic, "go"))
        assertEquals(SemanticHighlighting.CONFIGURED_BY_THEME, snapshot.get(semantic, "python"))
    }
}
