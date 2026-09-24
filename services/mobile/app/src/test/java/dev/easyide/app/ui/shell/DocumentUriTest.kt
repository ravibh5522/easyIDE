package dev.easyide.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentUriTest {

    /** input to canonical text, or null when it must be rejected. */
    private val cases: List<Pair<String, String?>> = listOf(
        "file:///workspace/src/a.kt" to "file:///workspace/src/a.kt",
        "FILE:///workspace/a.kt" to "file:///workspace/a.kt",
        "file:///workspace/./src/../a.kt" to "file:///workspace/a.kt",
        "file:///workspace//a.kt/" to "file:///workspace/a.kt",
        "file:///workspace/my%20file.kt" to "file:///workspace/my%20file.kt",
        "file:///workspace/caf%C3%A9.kt" to "file:///workspace/caf%C3%A9.kt",
        "file:///workspace/%61.kt" to "file:///workspace/a.kt",
        "file:///workspace/%2e%2e/x" to "file:///x",
        "file:///workspace/my file.kt" to null,
        "file:///workspace/a\tb" to null,
        "file:///../x" to null,
        "file:///%2E%2E/x" to null,
        "file:///" to null,
        "file://host/a" to null,
        "file:///a%2Fb" to null,
        "file:///a%zz" to null,
        "file:///a%C3" to null,
        "file:///a%" to null,
        "file:///a%00" to null,
        "easyide://settings/editor" to "easyide://settings/editor",
        "easyide://settings" to "easyide://settings",
        "easyide://settings/editor#fonts" to "easyide://settings/editor#fonts",
        "easyide://settings/editor#" to "easyide://settings/editor",
        "EASYIDE://Settings/Editor" to "easyide://settings/Editor",
        "easyide://settings/a/b" to null,
        "easyide://extension/easyide.python" to "easyide://extension/easyide.python",
        "easyide://extension" to null,
        "easyide://keybindings" to "easyide://keybindings",
        "easyide://keybindings/x" to null,
        "easyide://welcome" to "easyide://welcome",
        "easyide://" to null,
        "easyide://future-page/a/b" to "easyide://future-page/a/b",
        "git-diff:///workspace/a.kt?base=HEAD&target=working" to "git-diff:///workspace/a.kt?base=HEAD&target=working",
        "git-diff:///workspace/a.kt?target=working&base=HEAD" to "git-diff:///workspace/a.kt?base=HEAD&target=working",
        "git-diff:///workspace/a.kt?base=1&base=2" to null,
        "git-diff:///workspace/a.kt?=x" to null,
        "git-diff:///workspace/a.kt?flag" to "git-diff:///workspace/a.kt?flag=",
        "git-diff:///workspace/a.kt?ref=a%26b" to "git-diff:///workspace/a.kt?ref=a%26b",
        "git-diff://x/a.kt" to null,
        "ext://acme.docker/container/9f2c" to "ext://acme.docker/container/9f2c",
        "ext://Acme.Docker/container/9f2c" to "ext://acme.docker/container/9f2c",
        "ext://acme.docker/container" to null,
        "ext:///container/x" to null,
        "ext://acme.docker/a/b/c" to null,
        "git-commit://abc123" to "git-commit://abc123",
        "git-commit://" to null,
        "terminal://t1" to "terminal://t1",
        "terminal://t1/x" to null,
        "preview:///workspace/README.md" to "preview:///workspace/README.md",
        "custom://foo/bar" to "custom://foo/bar",
        "notauri" to null,
        "://x" to null,
        "" to null,
        "1x://a" to null,
        "file:/workspace/a" to null,
        "ext://bad_host!/a/b" to null,
    )

    @Test
    fun `parse canonicalises or rejects every case`() {
        cases.forEach { (input, expected) ->
            assertEquals("parse($input)", expected, DocumentUri.parse(input)?.toString())
        }
    }

    @Test
    fun `canonical text parses back to an equal value`() {
        cases.mapNotNull { it.second }.forEach { canonical ->
            val again = DocumentUri.parse(canonical)
            assertEquals(canonical, again?.toString())
            assertEquals(DocumentUri.parse(canonical), again)
        }
    }

    @Test
    fun `different spellings of one location are equal and hash alike`() {
        val a = uri("FILE:///workspace/./src/../a.kt")
        val b = uri("file:///workspace/a.kt")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(uri("file:///workspace/a.kt"), uri("file:///workspace/A.kt"))
    }

    @Test
    fun `decoded components are exposed`() {
        val u = uri("git-diff:///workspace/my%20dir/a.kt?base=HEAD%7E1&target=working#L3")
        assertEquals("git-diff", u.scheme)
        assertEquals(listOf("workspace", "my dir", "a.kt"), u.segments)
        assertEquals("/workspace/my dir/a.kt", u.path)
        assertEquals(mapOf("base" to "HEAD~1", "target" to "working"), u.query)
        assertEquals("L3", u.fragment)
        assertEquals("a.kt", u.name)
        assertEquals("settings", uri("easyide://settings").name)
    }

    @Test
    fun `the key drops the sub page and equals the bare document`() {
        val fonts = uri("easyide://settings/editor#fonts")
        assertEquals(uri("easyide://settings/editor"), fonts.key)
        assertEquals(fonts.key, fonts.key.key)
        assertNotEquals(fonts, fonts.key)
        assertEquals(fonts, fonts.key.withFragment("fonts"))
        assertEquals(fonts.key, fonts.withFragment(null))
        assertEquals(fonts.key, fonts.withFragment(""))
    }

    @Test
    fun `factories build the spec forms and reject bad input`() {
        assertEquals("file:///workspace/a.kt", DocumentUri.file("/workspace/a.kt").toString())
        assertNull(DocumentUri.file("workspace/a.kt"))
        assertNull(DocumentUri.file("/"))
        assertNull(DocumentUri.file("/../a"))
        assertEquals("preview:///workspace/README.md", DocumentUri.preview("/workspace/README.md").toString())
        assertEquals(
            "git-diff:///workspace/a.kt?base=HEAD&target=working",
            DocumentUri.gitDiff("/workspace/a.kt", "HEAD", "working").toString(),
        )
        assertEquals("git-commit://abc", DocumentUri.gitCommit("abc").toString())
        assertNull(DocumentUri.gitCommit(""))
        assertEquals("terminal://7", DocumentUri.terminal("7").toString())
        assertEquals("easyide://settings/editor#fonts", DocumentUri.easyide("settings", "editor", "fonts").toString())
        assertNull(DocumentUri.easyide("settings", "a/b"))
        assertEquals("ext://acme.docker/container/9f2c", DocumentUri.extension("acme.docker", "container", "9f2c").toString())
        assertNull(DocumentUri.extension("acme.docker", "", "x"))
        assertNull(DocumentUri.extension("", "a", "b"))
    }

    @Test
    fun `a file name with reserved characters survives a round trip`() {
        val u = DocumentUri.file("/workspace/a b&c=d#e?f%g+h.kt")!!
        assertEquals(listOf("workspace", "a b&c=d#e?f%g+h.kt"), u.segments)
        assertEquals(u, DocumentUri.parse(u.toString()))
        assertEquals("file:///workspace/a%20b&c=d%23e%3Ff%25g+h.kt", u.toString())
    }

    @Test
    fun `built in pages are valid`() {
        assertEquals("easyide://welcome", DocumentUri.WELCOME.toString())
        assertEquals("easyide://keybindings", DocumentUri.KEYBINDINGS.toString())
        assertEquals("easyide://language-servers", DocumentUri.LANGUAGE_SERVERS.toString())
        assertEquals("easyide://diagnostics", DocumentUri.DIAGNOSTICS.toString())
    }
}
