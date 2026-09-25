package dev.easyide.sandbox.git

import org.junit.Assert.assertEquals
import org.junit.Test

class GitFailureClassifierTest {

    private fun kind(text: String) = GitFailureClassifier.classify(text)

    @Test fun `missing credential with prompts disabled is auth`() {
        assertEquals(
            GitFailureKind.AUTH,
            kind("fatal: could not read Username for 'https://github.com': terminal prompts disabled"),
        )
    }

    @Test fun `rejected token is auth even though the message also says unable to access`() {
        val text = "fatal: unable to access 'https://github.com/a/b.git/': The requested URL returned error: 403"
        assertEquals(GitFailureKind.AUTH, kind(text))
    }

    @Test fun `bad password is auth`() {
        assertEquals(GitFailureKind.AUTH, kind("remote: Invalid username or password.\nfatal: Authentication failed for 'https://x/'"))
    }

    @Test fun `push rejected because remote is ahead is non fast forward`() {
        val text = """
            To https://github.com/a/b.git
             ! [rejected]        main -> main (non-fast-forward)
            error: failed to push some refs to 'https://github.com/a/b.git'
        """.trimIndent()
        assertEquals(GitFailureKind.NON_FAST_FORWARD, kind(text))
    }

    @Test fun `fetch first rejection is non fast forward`() {
        assertEquals(
            GitFailureKind.NON_FAST_FORWARD,
            kind("hint: Updates were rejected because the remote contains work that you do not have locally."),
        )
    }

    @Test fun `push of a branch without upstream`() {
        assertEquals(
            GitFailureKind.NO_UPSTREAM,
            kind("fatal: The current branch feat has no upstream branch.\nTo push the current branch and set the remote as upstream, use"),
        )
    }

    @Test fun `pull without tracking information`() {
        assertEquals(GitFailureKind.NO_UPSTREAM, kind("There is no tracking information for the current branch."))
    }

    @Test fun `ff only pull on diverged history`() {
        assertEquals(GitFailureKind.DIVERGED, kind("fatal: Not possible to fast-forward, aborting."))
    }

    @Test fun `merge conflicts`() {
        assertEquals(
            GitFailureKind.MERGE_CONFLICT,
            kind("CONFLICT (content): Merge conflict in a.txt\nAutomatic merge failed; fix conflicts and then commit the result."),
        )
    }

    @Test fun `local changes block pull`() {
        assertEquals(
            GitFailureKind.DIRTY_TREE,
            kind("error: Your local changes to the following files would be overwritten by merge:\n\ta.txt"),
        )
    }

    @Test fun `dns failure is network`() {
        assertEquals(GitFailureKind.NETWORK, kind("fatal: unable to access 'https://x/': Could not resolve host: x"))
    }

    @Test fun `anything else is unknown`() {
        assertEquals(GitFailureKind.UNKNOWN, kind("fatal: something new"))
        assertEquals(GitFailureKind.UNKNOWN, kind(""))
    }
}
