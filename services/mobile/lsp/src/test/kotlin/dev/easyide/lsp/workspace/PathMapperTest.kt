package dev.easyide.lsp.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PathMapperTest {
    private val project = File("/data/app/files/sandbox/projects/p1")
    private val rootfs = File("/data/app/files/sandbox/environments/e1/rootfs")
    private val mapper = WorkspacePathMapper(project, rootfs, "/workspace", listOf("/dev", "/proc", "/sys"))

    @Test
    fun projectFilesRoundTrip() {
        val host = File(project, "src/main.py")
        val uri = mapper.toGuestUri(host)!!
        assertEquals("file:///workspace/src/main.py", uri)
        val back = mapper.toHost(uri)!!
        assertEquals(host, back.file)
        assertFalse(back.readOnly)
        assertEquals("src/main.py", back.projectRelative)
        assertEquals("file:///workspace", mapper.rootUri)
    }

    @Test
    fun spacesPercentAndUnicodeAreEncodedLikeVscodeUri() {
        val host = File(project, "my dir/100% ünïcode 𝄞.py")
        val uri = mapper.toGuestUri(host)!!
        assertEquals("file:///workspace/my%20dir/100%25%20%C3%BCn%C3%AFcode%20%F0%9D%84%9E.py", uri)
        assertEquals(host, mapper.toHost(uri)!!.file)
    }

    @Test
    fun inputSpellingsAreNormalised() {
        val expected = File(project, "a/b.py")
        for (u in listOf("file:///workspace/a/b.py", "file:/workspace/a/b.py", "file://localhost/workspace/a/b.py", "FILE:///workspace/./a//b.py", "file:///workspace/a/x/../b.py", "file:///workspace/a/b%2epy")) {
            assertEquals(u, expected, mapper.toHost(u)?.file)
        }
        assertEquals(project, mapper.toHost("file:///workspace/")!!.file)
    }

    @Test
    fun environmentFilesAreReadOnlyRootfsPaths() {
        val loc = mapper.toHost("file:///usr/lib/python3.12/os.py")!!
        assertEquals(File(rootfs, "usr/lib/python3.12/os.py"), loc.file)
        assertTrue(loc.readOnly)
        assertNull(loc.projectRelative)
        assertEquals("file:///usr/lib/python3.12/os.py", mapper.toGuestUri(loc.file))
    }

    @Test
    fun unmappableLocationsAreNull() {
        assertNull(mapper.toHost("file:///proc/self/status"))
        assertNull(mapper.toHost("file:///dev/null"))
        assertNull(mapper.toHost("untitled:Untitled-1"))
        assertNull(mapper.toHost("jdt://contents/rt.jar/java.lang/String.class"))
        assertNull(mapper.toHost("file://otherhost/share/x"))
        assertNull(mapper.toHost("file:///workspace/%zz"))
        // `..` climbing above the root is refused, not clamped.
        assertNull(mapper.toHost("file:///../../etc/passwd"))
        // A rootfs path the guest sees as the workspace bind is shadowed.
        assertNull(mapper.toGuestUri(File(rootfs, "workspace/x.py")))
        assertNull(mapper.toGuestUri(File("/somewhere/else.py")))
    }

    @Test
    fun dotDotInsideTheWorkspaceCannotEscapeIt() {
        val loc = mapper.toHost("file:///workspace/../etc/passwd")!!
        // Lexically this is /etc/passwd in the guest: a read-only environment file, not a project file.
        assertTrue(loc.readOnly)
        assertEquals(File(rootfs, "etc/passwd"), loc.file)
    }

    @Test
    fun canonicalSpellingIsStable() {
        assertEquals("file:///workspace/a%20b", FileUri.canonical("file:/workspace/a b"))
        assertEquals("untitled:x", FileUri.canonical("untitled:x"))
    }
}
