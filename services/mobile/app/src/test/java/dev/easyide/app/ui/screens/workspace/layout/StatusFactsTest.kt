package dev.easyide.app.ui.screens.workspace.layout

import dev.easyide.sandbox.git.GitChange
import dev.easyide.sandbox.git.GitChangeType
import dev.easyide.sandbox.git.GitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusFactsTest {

    @Test
    fun `caret is one based`() {
        assertEquals(CaretPosition(1, 1), StatusFacts.caret("", 0))
        assertEquals(CaretPosition(1, 4), StatusFacts.caret("abc\ndef", 3))
        assertEquals(CaretPosition(2, 1), StatusFacts.caret("abc\ndef", 4))
        assertEquals(CaretPosition(2, 4), StatusFacts.caret("abc\ndef", 7))
    }

    @Test
    fun `a stale offset clamps into the text`() {
        assertEquals(CaretPosition(2, 4), StatusFacts.caret("abc\ndef", 500))
        assertEquals(CaretPosition(1, 1), StatusFacts.caret("abc", -3))
    }

    @Test
    fun `a trailing newline puts the caret on an empty last line`() {
        assertEquals(CaretPosition(3, 1), StatusFacts.caret("a\nb\n", 4))
    }

    @Test
    fun `line ending is read from the first line`() {
        assertEquals(LineEnding.CRLF, StatusFacts.lineEnding("a\r\nb\n"))
        assertEquals(LineEnding.LF, StatusFacts.lineEnding("a\nb\r\n"))
        assertEquals(LineEnding.LF, StatusFacts.lineEnding("no newline"))
        assertEquals(LineEnding.LF, StatusFacts.lineEnding(""))
        assertEquals(LineEnding.LF, StatusFacts.lineEnding("\nx"))
    }

    private fun change(path: String) = GitChange(path, GitChangeType.MODIFIED, staged = false)

    @Test
    fun `git summary counts every kind of change`() {
        val status = GitStatus(
            branch = "main",
            staged = listOf(change("a")),
            unstaged = listOf(change("b"), change("c")),
            conflicting = emptyList(),
            isClean = false,
            ahead = 2,
            behind = 1,
        )
        assertEquals(GitSummary("main", ahead = 2, behind = 1, changed = 3), GitSummary.of(true, status))
    }

    @Test
    fun `no summary before the first read or outside a repository`() {
        assertNull(GitSummary.of(isRepository = true, status = null))
        assertNull(GitSummary.of(isRepository = false, status = GitStatus.NONE))
        assertNull(GitSummary.of(isRepository = true, status = GitStatus.NONE))
    }

    @Test
    fun `accessory bar modes`() {
        assertEquals(true, AccessoryBarMode.AUTO.isVisible(hardwareKeyboardAttached = false))
        assertEquals(false, AccessoryBarMode.AUTO.isVisible(hardwareKeyboardAttached = true))
        assertEquals(true, AccessoryBarMode.ALWAYS.isVisible(hardwareKeyboardAttached = true))
        assertEquals(false, AccessoryBarMode.NEVER.isVisible(hardwareKeyboardAttached = false))
    }
}
