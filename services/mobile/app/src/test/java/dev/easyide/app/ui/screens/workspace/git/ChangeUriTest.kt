package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.app.ui.shell.diff.Comparison
import dev.easyide.sandbox.git.GitChange
import dev.easyide.sandbox.git.GitChangeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChangeUriTest {
    @Test fun `a staged change opens HEAD against the index`() {
        val change = GitChange("src/a.kt", GitChangeType.MODIFIED, staged = true)
        assertEquals(Comparison.staged("src/a.kt").uri, changeUri(change))
    }

    @Test fun `an unstaged or untracked change opens the index against the working tree`() {
        for (type in listOf(GitChangeType.MODIFIED, GitChangeType.DELETED, GitChangeType.UNTRACKED)) {
            assertEquals(Comparison.unstaged("a.txt").uri, changeUri(GitChange("a.txt", type, staged = false)))
        }
    }

    @Test fun `a conflict opens the file, not a diff`() {
        assertNull(changeUri(GitChange("a.txt", GitChangeType.CONFLICTED, staged = false)))
    }

    @Test fun `a path a uri cannot carry has no diff to open`() {
        assertNull(changeUri(GitChange("bad\nname", GitChangeType.MODIFIED, staged = false)))
    }
}
