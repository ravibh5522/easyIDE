package dev.easyide.sandbox.extensions

import dev.easyide.sandbox.SandboxPaths
import dev.easyide.sandbox.backend.GuestBind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class EnvironmentExtensionBindsTest {

    @get:Rule val temp = TemporaryFolder()

    private val paths by lazy { SandboxPaths(temp.root) }
    private val scope by lazy { paths.environmentExtensionsDir(ENV) }

    /** Installs [version] of [id] the way the store will: a version dir plus a relative `current` link. */
    private fun install(id: String, version: String, linkTarget: String = version): File {
        val dir = File(scope, id).apply { mkdirs() }
        val versionDir = File(dir, version).apply { mkdirs() }
        Files.createSymbolicLink(File(dir, "current").toPath(), Paths.get(linkTarget))
        return versionDir.canonicalFile
    }

    private fun binds(isEnabled: (String, ExtensionId) -> Boolean = { _, _ -> true }) =
        EnvironmentExtensionBinds(paths, isEnabled).bindsFor(ENV)

    @Test fun `no extensions dir means no binds`() {
        assertEquals(emptyList<GuestBind>(), binds())
    }

    @Test fun `each installed extension binds its resolved current version`() {
        val python = install("easyide.python", "1.2.0")
        val go = install("easyide.go", "0.3.1-beta.2")

        assertEquals(
            listOf(
                GuestBind(go, "/opt/easyide/extensions/easyide.go"),
                GuestBind(python, "/opt/easyide/extensions/easyide.python"),
            ),
            binds(),
        )
    }

    @Test fun `disabled extensions are not bound`() {
        install("easyide.python", "1.2.0")
        val go = install("easyide.go", "1.0.0")

        val result = binds { env, id -> env == ENV && id != ExtensionId.parse("easyide.python") }

        assertEquals(listOf(GuestBind(go, "/opt/easyide/extensions/easyide.go")), result)
    }

    @Test fun `malformed installs are skipped rather than bound`() {
        val good = install("easyide.good", "1.0.0")
        // Non-canonical or invalid names: not written by the store.
        install("EasyIDE.Upper", "1.0.0")
        install("not-an-id", "1.0.0")
        // current escapes its extension dir.
        File(temp.root, "outside/1.0.0").mkdirs()
        install("easyide.escape", "1.0.0", linkTarget = "../../../../outside/1.0.0")
        // current points at a non-version name.
        install("easyide.tmp", ".tmp-1.0.0-1", linkTarget = ".tmp-1.0.0-1")
        // Dangling current: interrupted install.
        File(scope, "easyide.dangling").mkdirs()
        Files.createSymbolicLink(File(scope, "easyide.dangling/current").toPath(), Paths.get("9.9.9"))
        // current is a plain directory, not a link.
        File(scope, "easyide.plain/current").mkdirs()
        // A stray file at scope level.
        File(scope, "easyide.file").writeText("x")

        assertEquals(listOf(GuestBind(good, "/opt/easyide/extensions/easyide.good")), binds())
    }

    private companion object {
        const val ENV = "env1"
    }
}
