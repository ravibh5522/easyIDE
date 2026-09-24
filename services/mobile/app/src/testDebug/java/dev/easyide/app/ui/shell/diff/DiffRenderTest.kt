package dev.easyide.app.ui.shell.diff

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.easyide.app.ui.theme.EasyIdeTheme
import dev.easyide.app.ui.theme.ThemeMode
import dev.easyide.sandbox.git.DiffHunk
import dev.easyide.sandbox.git.DiffLine
import dev.easyide.sandbox.git.DiffLineKind
import dev.easyide.sandbox.git.FileDiff
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The diff document's list and header compose in both layouts and expose their actions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DiffRenderTest {

    @get:Rule val compose = createComposeRule()

    private val hunk = DiffHunk(
        1, 3, 1, 3, "",
        listOf(
            DiffLine(DiffLineKind.CONTEXT, "keep"),
            DiffLine(DiffLineKind.REMOVED, "old name"),
            DiffLine(DiffLineKind.ADDED, "new name"),
            DiffLine(DiffLineKind.CONTEXT, "tail"),
        ),
    )
    private val diff = FileDiff.Text("src/a.kt", listOf(hunk))

    private class Recorder : HunkActions {
        val performed = ArrayList<HunkAction>()
        override val available = listOf(HunkAction.STAGE, HunkAction.DISCARD)
        override val busy = flowOf(false)
        override fun perform(action: HunkAction, hunk: DiffHunk) { performed += action }
    }

    private fun show(content: @Composable () -> Unit) = compose.setContent { EasyIdeTheme(themeMode = ThemeMode.DARK) { content() } }

    private fun list(mode: DiffMode, actions: HunkActions?, busy: Boolean = false) = show {
        DiffList(DiffListSpec(DiffModel.of(diff, mode), DiffPaint.NONE, actions, busy), rememberLazyListState())
    }

    @Test fun `unified lines and the hunk header are drawn`() {
        list(DiffMode.UNIFIED, null)
        compose.onNodeWithText(hunk.header).assertIsDisplayed()
        compose.onNodeWithText("keep").assertIsDisplayed()
        compose.onNodeWithText("tail").assertIsDisplayed()
    }

    @Test fun `side by side draws the paired lines of a change`() {
        list(DiffMode.SIDE_BY_SIDE, null)
        // Unchanged lines show on both sides; a change shows its old text on the left and its new text on the right.
        compose.onAllNodesWithText("keep").assertCountEquals(2)
        compose.onAllNodesWithText("tail").assertCountEquals(2)
        compose.onNodeWithText("old name").assertIsDisplayed()
        compose.onNodeWithText("new name").assertIsDisplayed()
    }

    @Test fun `hunk actions call the provider and follow busy`() {
        val actions = Recorder()
        list(DiffMode.UNIFIED, actions)
        compose.onNodeWithText("Stage hunk").performClick()
        compose.onNodeWithText("Discard hunk").performClick()
        assertEquals(listOf(HunkAction.STAGE, HunkAction.DISCARD), actions.performed)
    }

    @Test fun `a busy diff disables its hunk actions`() {
        list(DiffMode.UNIFIED, Recorder(), busy = true)
        compose.onNodeWithText("Stage hunk").assertIsNotEnabled()
    }

    @Test fun `a read only diff has no hunk actions`() {
        list(DiffMode.UNIFIED, null)
        compose.onNodeWithText("Stage hunk").assertDoesNotExist()
    }

    @Test fun `the header names the file, its sides and where the reader is among the hunks`() {
        var next = 0
        show {
            DiffHeader(
                DiffSubject("src/a.kt", SideLabel.Index, SideLabel.Worktree),
                3 to 1,
                HunkNav(position = 1, count = 4, onPrevious = null, onNext = { next++ }),
                onOpenFile = {},
            )
        }
        compose.onNodeWithText("a.kt").assertIsDisplayed()
        compose.onNodeWithText("src").assertIsDisplayed()
        compose.onNodeWithText("Index to Working tree").assertIsDisplayed()
        compose.onNodeWithText("2 of 4").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous change").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next change").assertIsEnabled().performClick()
        assertEquals(1, next)
    }
}
