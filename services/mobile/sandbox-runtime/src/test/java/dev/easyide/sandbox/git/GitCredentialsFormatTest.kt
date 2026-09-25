package dev.easyide.sandbox.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitCredentialsFormatTest {

    @Test fun `round trip keeps host token and username`() {
        val a = GitCredential("github.com", "ada", "ghp_abc")
        val b = GitCredential("gitlab.com", "oauth2", "glpat-xyz")
        val decoded = GitCredentialsFormat.decode(GitCredentialsFormat.encode(listOf(a, b)))
        assertEquals(mapOf("github.com" to a, "gitlab.com" to b), decoded)
    }

    @Test fun `files written before usernames existed still read and get the default user`() {
        val decoded = GitCredentialsFormat.decode("github.com\tghp_old\ngitlab.com\tglpat-old")
        assertEquals(GitCredential("github.com", GitCredentialsFormat.DEFAULT_USERNAME, "ghp_old"), decoded["github.com"])
        assertEquals("glpat-old", decoded["gitlab.com"]?.token)
    }

    @Test fun `empty plaintext decodes to nothing`() {
        assertTrue(GitCredentialsFormat.decode("").isEmpty())
    }

    @Test fun `separators and blanks are refused before they can corrupt the file`() {
        assertTrue(GitCredentialsFormat.isStorable("github.com", "tok", "ada"))
        assertFalse(GitCredentialsFormat.isStorable("github.com", "", "ada"))
        assertFalse(GitCredentialsFormat.isStorable("", "tok", "ada"))
        assertFalse(GitCredentialsFormat.isStorable("github.com", "to\tk", "ada"))
        assertFalse(GitCredentialsFormat.isStorable("github.com", "tok", "a\nda"))
    }
}

class GitUrlTest {
    @Test fun `host of a url and of a bare host`() {
        assertEquals("github.com", GitUrl.host("https://github.com/a/b.git"))
        assertEquals("gitlab.example.org", GitUrl.host(" https://User@GitLab.Example.org:8443/a/b "))
        assertEquals("github.com", GitUrl.host("GitHub.com"))
    }

    @Test fun `ssh style and junk have no host`() {
        assertEquals(null, GitUrl.host("git@github.com:a/b.git"))
        assertEquals(null, GitUrl.host(""))
        assertEquals(null, GitUrl.host("not a host"))
    }
}
