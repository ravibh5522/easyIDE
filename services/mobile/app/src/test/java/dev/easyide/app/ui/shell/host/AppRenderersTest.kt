package dev.easyide.app.ui.shell.host

import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.DocumentType
import dev.easyide.app.ui.shell.NavEnv
import dev.easyide.app.ui.shell.NavPrefs
import dev.easyide.app.ui.shell.NavTarget
import dev.easyide.app.ui.shell.ShellScope
import dev.easyide.app.ui.shell.Placement
import dev.easyide.app.ui.shell.uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRenderersTest {
    private val registries = AppDocuments.registries { "text" }

    @Test fun `every app-scope container has a panel renderer and no other id does`() {
        val appContainers = Placement.entries.flatMap { registries.containers.inPlacement(it, ShellScope.APP) }.map { it.id }.toSet()
        assertEquals(appContainers, AppRenderers.containerIds)
    }

    @Test fun `every app navigation item leads to a container that has a renderer`() {
        val items = registries.navigation.visible(ShellScope.APP, NavPrefs(), NavEnv({ true }, { true }))
        assertEquals(listOf(CoreShell.HOME, CoreShell.EXTENSIONS, CoreShell.SETTINGS), items.map { it.id })
        items.forEach { assertTrue(it.id, (it.target as NavTarget.Container).id in AppRenderers.containerIds) }
    }

    @Test fun `every app document type has a renderer and every renderer has a type`() {
        val samples = listOf("easyide://settings", "easyide://settings/editor#fonts", "easyide://extension/easyide.python", "easyide://project/p1")
        val types = samples.map { registries.documents.resolve(uri(it)).id }
        assertFalse(DocumentType.UNAVAILABLE_ID in types)
        assertEquals(types.toSet(), AppRenderers.documentTypeIds)
    }

    @Test fun `subjects are read from their own page only`() {
        assertEquals("p1", AppDocuments.projectIdOf(uri("easyide://project/p1")))
        assertEquals("easyide.python", AppDocuments.extensionIdOf(uri("easyide://extension/easyide.python")))
        assertEquals("editor", AppDocuments.settingsCategoryOf(uri("easyide://settings/editor#fonts")))
        assertNull(AppDocuments.settingsCategoryOf(uri("easyide://settings")))
        assertNull(AppDocuments.projectIdOf(uri("easyide://extension/p1")))
        assertNull(AppDocuments.projectIdOf(null))
    }
}
