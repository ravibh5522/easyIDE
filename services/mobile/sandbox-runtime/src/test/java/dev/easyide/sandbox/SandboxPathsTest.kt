package dev.easyide.sandbox

import dev.easyide.sandbox.extensions.ExtensionId
import dev.easyide.sandbox.extensions.ExtensionVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SandboxPathsTest {

    @get:Rule val temp = TemporaryFolder()

    private val root by lazy { temp.root }
    private val paths by lazy { SandboxPaths(root) }
    private val id = ExtensionId.parse("EasyIDE.Python")
    private val version = ExtensionVersion.parse("1.4.0-rc.1")

    @Test fun `extension layout matches registry-and-install sec 11`() {
        assertEquals(File(root, "extensions"), paths.extensionsDir)
        assertEquals(File(root, "extensions/global"), paths.globalExtensionsDir)
        assertEquals(File(root, "extensions/staging"), paths.extensionStagingDir)
        assertEquals(File(root, "extensions/state.json"), paths.extensionStateFile)
        assertEquals(File(root, "environments/env1/extensions"), paths.environmentExtensionsDir("env1"))
    }

    @Test fun `per-extension paths use the canonical id under the given scope`() {
        val scope = paths.environmentExtensionsDir("env1")

        assertEquals(File(scope, "easyide.python"), paths.extensionDir(scope, id))
        assertEquals(File(scope, "easyide.python/1.4.0-rc.1"), paths.extensionVersionDir(scope, id, version))
        assertEquals(File(scope, "easyide.python/current"), paths.extensionCurrentLink(scope, id))
        assertEquals(
            File(paths.globalExtensionsDir, "easyide.python/current"),
            paths.extensionCurrentLink(paths.globalExtensionsDir, id),
        )
    }

    @Test fun `every derived extension path stays inside its scope dir`() {
        val scope = paths.globalExtensionsDir.canonicalFile
        val derived = listOf(
            paths.extensionDir(scope, id),
            paths.extensionVersionDir(scope, id, version),
            paths.extensionCurrentLink(scope, id),
        )
        derived.forEach { assertTrue(it.canonicalPath.startsWith(scope.path + File.separator)) }
    }

    @Test fun `guest extension path is the sdk-reference extensionPath`() {
        assertEquals("/opt/easyide/extensions/easyide.python", paths.guestExtensionPath(id))
    }

    @Test fun `ensureBaseDirs creates the extension skeleton`() {
        paths.ensureBaseDirs()

        assertTrue(paths.globalExtensionsDir.isDirectory)
        assertTrue(paths.extensionStagingDir.isDirectory)
    }
}
