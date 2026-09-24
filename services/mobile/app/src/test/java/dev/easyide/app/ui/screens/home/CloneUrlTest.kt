package dev.easyide.app.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class CloneUrlTest {

    private fun valid(input: String) = (CloneUrl.parse(input) as CloneUrl.Result.Valid).value
    private fun problem(input: String) = (CloneUrl.parse(input) as CloneUrl.Result.Invalid).problem

    @Test fun `https urls are accepted and trimmed, with the repository name suggested`() {
        assertEquals(CloneUrl("https://github.com/o/api-svc.git", "api-svc"), valid("  https://github.com/o/api-svc.git \n"))
        assertEquals("repo", valid("https://host.example/group/sub/repo").suggestedName)
        assertEquals("repo", valid("https://host/o/repo/").suggestedName)
        assertEquals("repo", valid("http://host/o/repo.git?x=1#f").suggestedName)
    }

    @Test fun `a bare host has no suggested name`() {
        assertEquals("", valid("https://github.com").suggestedName)
    }

    @Test fun `empty input`() {
        assertEquals(CloneUrl.Problem.EMPTY, problem("   "))
    }

    @Test fun `ssh forms are refused as unsupported`() {
        assertEquals(CloneUrl.Problem.UNSUPPORTED_SCHEME, problem("git@github.com:o/r.git"))
        assertEquals(CloneUrl.Problem.UNSUPPORTED_SCHEME, problem("ssh://git@host/o/r"))
        assertEquals(CloneUrl.Problem.UNSUPPORTED_SCHEME, problem("git://host/o/r"))
    }

    @Test fun `anything that could be read as a git option or contains whitespace is malformed`() {
        assertEquals(CloneUrl.Problem.MALFORMED, problem("--upload-pack=touch /tmp/x"))
        assertEquals(CloneUrl.Problem.MALFORMED, problem("-oProxyCommand=x"))
        assertEquals(CloneUrl.Problem.MALFORMED, problem("https://host/o/r; rm -rf ~"))
        assertEquals(CloneUrl.Problem.MALFORMED, problem("file:///data/x"))
        assertEquals(CloneUrl.Problem.MALFORMED, problem("github.com/o/r"))
    }

    @Test fun `clone failures are classified from git output`() {
        assertEquals(CloneFailure.AUTHENTICATION, classifyCloneFailure("fatal: could not read Username for 'https://github.com': No such device"))
        assertEquals(CloneFailure.AUTHENTICATION, classifyCloneFailure("remote: HTTP Basic: Access denied\nfatal: Authentication failed for 'x'"))
        assertEquals(CloneFailure.NOT_FOUND, classifyCloneFailure("remote: Repository not found.\nfatal: repository 'x' not found"))
        assertEquals(CloneFailure.OTHER, classifyCloneFailure("git: command not found"))
    }

    @Test fun `last output line skips blanks and carriage-return progress`() {
        assertEquals("fatal: boom", lastOutputLine("Cloning...\rReceiving 50%\nfatal: boom\n\n"))
        assertEquals("", lastOutputLine(""))
    }
}
