package dev.easyide.app.ui.shell.host

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.easyide.app.ui.shell.DocumentType
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RendererRegistryTest {
    private val renderer = object : DocumentRenderer {
        @Composable
        override fun Render(uri: DocumentUri, modifier: Modifier) = Unit
    }
    private val registries = AppDocuments.registries { "text" }

    private fun typeOf(text: String) = registries.documents.resolve(uri(text))

    @Test fun `a registered type resolves to its renderer`() {
        val registry = DocumentRendererRegistry(mapOf(AppDocuments.SETTINGS to renderer))
        val choice = registry.choose(typeOf("easyide://settings/editor"))
        assertSame(renderer, (choice as DocumentChoice.Render).renderer)
    }

    @Test fun `a type with no renderer falls back to the placeholder`() {
        val registry = DocumentRendererRegistry(mapOf(AppDocuments.SETTINGS to renderer))
        assertEquals(DocumentChoice.Unavailable, registry.choose(typeOf("easyide://project/p1")))
    }

    @Test fun `an unknown uri resolves to the unavailable type and the placeholder`() {
        val type = typeOf("easyide://from-a-newer-app")
        assertEquals(DocumentType.UNAVAILABLE_ID, type.id)
        assertEquals(DocumentChoice.Unavailable, DocumentRendererRegistry().choose(type))
    }

    @Test fun `a renderer registered under the unavailable id is never used`() {
        val registry = DocumentRendererRegistry(mapOf(DocumentType.UNAVAILABLE_ID to renderer))
        assertEquals(DocumentChoice.Unavailable, registry.choose(DocumentType.UNAVAILABLE))
    }

    @Test fun `panel bindings are found by container id and absent otherwise`() {
        val panel = object : PanelRenderer {
            @Composable
            override fun Render(modifier: Modifier) = Unit
        }
        val binding = PanelBinding(panel)
        val registry = PanelRendererRegistry(mapOf("home.projects" to binding))
        assertSame(binding, registry.binding("home.projects"))
        assertNull(registry.binding("acme.docker.panel"))
    }

    @Test fun `the app document types resolve their pages`() {
        assertEquals(AppDocuments.SETTINGS, typeOf("easyide://settings").id)
        assertEquals(AppDocuments.SETTINGS, typeOf("easyide://settings/editor#fonts").id)
        assertEquals(AppDocuments.EXTENSION, typeOf("easyide://extension/easyide.python").id)
        assertEquals(AppDocuments.PROJECT, typeOf(AppDocuments.projectPage("p1").toString()).id)
    }

    @Test fun `page uris round trip through the factories`() {
        assertEquals("easyide://settings/editor", AppDocuments.settingsPage("editor").toString())
        assertEquals("easyide://settings", AppDocuments.settingsPage().toString())
        assertEquals("easyide://extension/easyide.python", AppDocuments.extensionPage("easyide.python").toString())
    }
}
