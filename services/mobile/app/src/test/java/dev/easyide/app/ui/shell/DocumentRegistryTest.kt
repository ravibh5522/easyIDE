package dev.easyide.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentRegistryTest {
    private val docker = Origin.Extension("acme.docker")

    private val settings = DocumentType("settings", UriPattern("easyide", "settings"), { "Settings" }, { IconRef("settings") })
    private val anyEasyide = type("easyide-any", "easyide")
    private val image = type("image", "image")
    private val preview = type("preview", "preview")
    private val container = extType("acme.docker/container")
    private val compose = extType("acme.docker/compose")

    private fun extType(id: String, ext: String = "acme.docker") =
        DocumentType(id, UriPattern("ext", ext, id.substringAfter('/')), { it.name }, { IconRef("doc") })

    private fun core(): DocumentRegistry = listOf(FILE_TYPE, settings, anyEasyide, image, preview, TERMINAL_TYPE)
        .fold(DocumentRegistry.EMPTY) { r, t -> r.register(t, Origin.Core).registry }
        .registerOpener(Opener("**/*.png", "image", OpenerPriority.BUILTIN), Origin.Core).registry
        .registerOpener(Opener("*.md", "preview", OpenerPriority.OPTION), Origin.Core).registry

    @Test
    fun `resolves by scheme and prefers the most specific pattern`() {
        val r = core()
        assertEquals("file", r.resolve(file("a.kt")).id)
        assertEquals("settings", r.resolve(uri("easyide://settings/editor")).id)
        assertEquals("easyide-any", r.resolve(uri("easyide://welcome")).id)
        assertEquals("terminal", r.resolve(terminal("1")).id)
    }

    @Test
    fun `unknown uris resolve to the unavailable placeholder`() {
        val r = core()
        val placeholder = r.resolve(uri("custom://x/y"))
        assertSame(DocumentType.UNAVAILABLE, placeholder)
        assertEquals("y", placeholder.title(uri("custom://x/y")))
        assertSame(DocumentType.UNAVAILABLE, DocumentRegistry.EMPTY.resolve(file("a.kt")))
    }

    @Test
    fun `an extension type is registered under its own namespace and removed with the pack`() {
        val added = core().register(container, docker)
        assertTrue(added.rejections.isEmpty())
        val u = uri("ext://acme.docker/container/9f2c")
        assertEquals("acme.docker/container", added.registry.resolve(u).id)
        assertSame(DocumentType.UNAVAILABLE, added.registry.unregister("acme.docker").resolve(u))
        assertEquals("file", added.registry.unregister("acme.docker").resolve(file("a.kt")).id)
    }

    @Test
    fun `registration refusals table`() {
        val base = core().register(container, docker).registry
        val foreignScheme = type("acme.docker/x", "file")
        val otherExt = DocumentType("acme.docker/y", UriPattern("ext", "other", "y"), { it.name }, { IconRef("doc") })
        val cases = listOf(
            Triple("duplicate id from another origin", extType("acme.docker/container") to docker, RejectReason.DUPLICATE_ID),
            Triple("core id taken by extension", DocumentType("file", UriPattern("ext", "acme.docker", "f"), { it.name }, { IconRef("d") }) to docker, RejectReason.DUPLICATE_ID),
            Triple("not namespaced", extType("container2") to docker, RejectReason.NOT_NAMESPACED),
            Triple("other pack's namespace", extType("acme.other/x", "acme.other") to docker, RejectReason.NOT_NAMESPACED),
            Triple("claims a core scheme", foreignScheme to docker, RejectReason.FOREIGN_SCHEME),
            Triple("claims another pack's uris", otherExt to docker, RejectReason.FOREIGN_SCHEME),
        )
        cases.forEach { (name, entry, reason) ->
            val (t, origin) = entry
            val out = base.register(t, origin)
            assertEquals(name, listOf(Rejection(t.id, reason)), out.rejections)
            assertSame(name, base, out.registry)
        }
    }

    @Test
    fun `the eleventh document type of one extension is refused`() {
        var r = DocumentRegistry.EMPTY
        repeat(ShellLimits.DOCUMENT_TYPES_PER_EXTENSION) { i -> r = r.register(extType("acme.docker/t$i"), docker).registry }
        val over = r.register(extType("acme.docker/t10"), docker)
        assertEquals(listOf(Rejection("acme.docker/t10", RejectReason.TOO_MANY)), over.rejections)
        assertNull(over.registry.typeById("acme.docker/t10"))
    }

    @Test
    fun `builtin openers beat the scheme type and option openers never do`() {
        val r = core()
        assertEquals("image", r.resolve(file("logo.png")).id)
        assertEquals("image", r.resolve(file("deep/dir/logo.png")).id)
        assertEquals("file", r.resolve(file("README.md")).id)
    }

    @Test
    fun `an extension default opener outranks core and an extension builtin opener is refused`() {
        val withCompose = core().register(compose, docker).registry
        val opened = withCompose.registerOpener(Opener("**/docker-compose*.yml", "acme.docker/compose", OpenerPriority.DEFAULT), docker)
        assertTrue(opened.rejections.isEmpty())
        assertEquals("acme.docker/compose", opened.registry.resolve(file("docker-compose.yml")).id)
        assertEquals("acme.docker/compose", opened.registry.resolve(file("infra/docker-compose.dev.yml")).id)
        assertEquals("file", opened.registry.resolve(file("other.yml")).id)

        val refused = withCompose.registerOpener(Opener("**/*.yml", "acme.docker/compose", OpenerPriority.BUILTIN), docker)
        assertEquals(listOf(Rejection("acme.docker/compose", RejectReason.RESERVED_PRIORITY)), refused.rejections)
        assertEquals("file", refused.registry.resolve(file("a.yml")).id)
    }

    @Test
    fun `an opener naming a missing type is skipped until the type appears`() {
        val r = core().registerOpener(Opener("*.yml", "acme.docker/compose", OpenerPriority.DEFAULT), docker).registry
        assertEquals("file", r.resolve(file("a.yml")).id)
        assertEquals("acme.docker/compose", r.register(compose, docker).registry.resolve(file("a.yml")).id)
    }

    @Test
    fun `equal priority openers resolve by registration order`() {
        val other = Origin.Extension("acme.other")
        val a = extType("acme.docker/compose")
        val b = extType("acme.other/compose", "acme.other")
        val r = core().register(a, docker).registry.register(b, other).registry
            .registerOpener(Opener("*.yml", "acme.other/compose", OpenerPriority.DEFAULT), other).registry
            .registerOpener(Opener("*.yml", "acme.docker/compose", OpenerPriority.DEFAULT), docker).registry
        assertEquals("acme.other/compose", r.resolve(file("a.yml")).id)
    }

    @Test
    fun `user associations beat openers and ignore unregistered types`() {
        val r = core()
        assertEquals("preview", r.resolve(file("README.md"), listOf(Association("*.md", "preview"))).id)
        assertEquals("file", r.resolve(file("README.md"), listOf(Association("*.md", "missing"))).id)
        assertEquals("file", r.resolve(file("logo.png"), listOf(Association("logo.png", "file"))).id)
        assertEquals(
            "preview",
            r.resolve(file("README.md"), listOf(Association("*.md", "missing"), Association("README.*", "preview"))).id,
        )
    }

    @Test
    fun `associations and openers only apply to files`() {
        val r = core()
        assertEquals("terminal", r.resolve(terminal("a.md"), listOf(Association("*", "preview"))).id)
    }

    @Test
    fun `open with lists the default first then the other offers without duplicates`() {
        val r = core()
        assertEquals(listOf("file", "preview"), r.alternatives(file("README.md")).map { it.id })
        assertEquals(listOf("image", "file"), r.alternatives(file("logo.png")).map { it.id })
        assertEquals(listOf("terminal"), r.alternatives(terminal("1")).map { it.id })
        assertEquals(listOf(DocumentType.UNAVAILABLE_ID), r.alternatives(uri("custom://x")).map { it.id })
    }

    @Test
    fun `glob dialect`() {
        val cases = listOf(
            Triple("*.md", "/workspace/a/README.md", true),
            Triple("*.md", "/workspace/README.txt", false),
            Triple("**/*.kt", "/workspace/a/b/c.kt", true),
            Triple("**/*.kt", "/workspace/c.kt", true),
            Triple("src/*.kt", "/src/a.kt", true),
            Triple("src/*.kt", "/src/deep/a.kt", false),
            Triple("src/**", "/src/deep/a.kt", true),
            Triple("a?.kt", "/workspace/ab.kt", true),
            Triple("a?.kt", "/workspace/abc.kt", false),
            Triple("a+b.kt", "/workspace/a+b.kt", true),
            Triple("a+b.kt", "/workspace/aab.kt", false),
        )
        cases.forEach { (glob, path, expected) ->
            assertEquals("$glob vs $path", expected, Association(glob, "t").matches(DocumentUri.file(path)!!))
        }
    }

    @Test
    fun `extension type ids map to patterns`() {
        assertEquals(UriPattern("ext", "acme.docker", "container"), UriPattern.forExtensionType("acme.docker/container"))
        listOf("container", "a/b/c", "/b", "a/", "").forEach { assertNull(it, UriPattern.forExtensionType(it)) }
    }
}
