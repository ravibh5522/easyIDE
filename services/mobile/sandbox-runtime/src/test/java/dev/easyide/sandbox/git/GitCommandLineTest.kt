package dev.easyide.sandbox.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitCommandLineTest {

    private val identity = GitIdentity("Ada Lovelace", "ada@example.com")

    @Test fun `fetch with no remote fetches all`() {
        assertEquals("fetch --prune --progress --all", GitCommandLine.subcommand(GitNetworkOp.Fetch()))
    }

    @Test fun `fetch of one remote quotes its name`() {
        assertEquals("fetch --prune --progress 'origin'", GitCommandLine.subcommand(GitNetworkOp.Fetch("origin")))
    }

    @Test fun `each pull strategy maps to its flag`() {
        assertEquals("pull --progress --no-rebase", GitCommandLine.subcommand(GitNetworkOp.Pull(PullStrategy.MERGE, null)))
        assertEquals("pull --progress --rebase", GitCommandLine.subcommand(GitNetworkOp.Pull(PullStrategy.REBASE, null)))
        assertEquals("pull --progress --ff-only", GitCommandLine.subcommand(GitNetworkOp.Pull(PullStrategy.FF_ONLY, null)))
    }

    @Test fun `pull passes identity as global config before the subcommand`() {
        val line = GitCommandLine.subcommand(GitNetworkOp.Pull(PullStrategy.MERGE, identity))
        assertEquals("-c user.name='Ada Lovelace' -c user.email='ada@example.com' pull --progress --no-rebase", line)
    }

    @Test fun `identity with a quote cannot break out of the shell word`() {
        val line = GitCommandLine.subcommand(GitNetworkOp.Pull(PullStrategy.MERGE, GitIdentity("O'Brien; rm -rf /", "o@x.y")))
        assertTrue(line.contains("user.name='O'\\''Brien; rm -rf /'"))
    }

    @Test fun `plain push sends only remote and branch when given`() {
        assertEquals("push --progress", GitCommandLine.subcommand(GitNetworkOp.Push(null, null)))
        assertEquals("push --progress 'origin' 'main'", GitCommandLine.subcommand(GitNetworkOp.Push("origin", "main")))
    }

    @Test fun `publish sets upstream and force uses lease never plain force`() {
        val publish = GitCommandLine.subcommand(GitNetworkOp.Push("origin", "feat", setUpstream = true))
        assertEquals("push --progress --set-upstream 'origin' 'feat'", publish)
        val force = GitCommandLine.subcommand(GitNetworkOp.Push("origin", "feat", forceWithLease = true))
        assertTrue(force.contains("--force-with-lease"))
        assertFalse(force.split(' ').contains("--force"))
    }

    @Test fun `credential helper is only present when a token is`() {
        val with = GitCommandLine.shellLine("fetch", withCredentials = true)
        val without = GitCommandLine.shellLine("fetch", withCredentials = false)
        assertTrue(with.contains("credential.helper="))
        assertFalse(without.contains("credential.helper"))
    }

    @Test fun `helper reads token and user from the environment and never contains a value`() {
        val line = GitCommandLine.shellLine("push", withCredentials = true)
        assertTrue(line.contains("password=\$EASYIDE_GIT_TOKEN"))
        assertTrue(line.contains("username=\$EASYIDE_GIT_USER"))
    }

    @Test fun `git never prompts and speaks english so errors can be classified`() {
        val line = GitCommandLine.shellLine("fetch", withCredentials = false)
        assertTrue(line.contains("GIT_TERMINAL_PROMPT=0"))
        assertTrue(line.contains("LC_ALL=C"))
        assertTrue(line.endsWith("2>&1"))
    }

    @Test fun `identity needs a name and a plausible email`() {
        assertEquals(identity, GitIdentity.of(" Ada Lovelace ", " ada@example.com "))
        assertNull(GitIdentity.of("", "ada@example.com"))
        assertNull(GitIdentity.of("Ada", "not-an-email"))
        assertNull(GitIdentity.of(null, null))
    }
}
