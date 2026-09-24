package dev.easyide.app.ui.screens.workspace.ext

import dev.easyide.app.ui.screens.workspace.lsp.LspLanguageFacts
import dev.easyide.lsp.manager.LspStateValue
import dev.easyide.lsp.protocol.LspFeature
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class LspContextKeysTest {

    @Test
    fun factsBecomeTheSdkReferenceKeys() {
        val keys = LspContextKeys.of(
            mapOf(
                "python" to LspLanguageFacts(LspStateValue.READY, ready = true, features = setOf(LspFeature.FORMATTING)),
                "go" to LspLanguageFacts(LspStateValue.STARTING, ready = false, features = emptySet()),
            ),
        )
        assertEquals(
            mapOf(
                "lspReady:python" to JsonPrimitive(true),
                "lspState:python" to JsonPrimitive("ready"),
                "lspSupports:python:formatting" to JsonPrimitive(true),
                "lspReady:go" to JsonPrimitive(false),
                "lspState:go" to JsonPrimitive("starting"),
            ),
            keys,
        )
    }
}
