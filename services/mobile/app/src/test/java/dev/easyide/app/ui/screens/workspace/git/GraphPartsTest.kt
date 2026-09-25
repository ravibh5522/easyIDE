package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.ui.graphics.Color
import dev.easyide.sandbox.git.GitChange
import dev.easyide.sandbox.git.GitChangeType
import dev.easyide.sandbox.git.GitCommit
import dev.easyide.sandbox.git.GitRef
import dev.easyide.sandbox.git.GitRefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphPartsTest {
    private fun commit(id: String, vararg parents: String) = GitCommit(id, id, parents.toList(), id, "", "a", "a@x", 0L)

    private fun rows(vararg commits: GitCommit) = CommitGraph.build(commits.toList())

    @Test fun `a straight history is one lane entering each dot from above and leaving below`() {
        val rows = rows(commit("c", "b"), commit("b", "a"), commit("a"))
        assertEquals(listOf(LaneSegment(0, 0, LaneY.MID, 0, LaneY.BOTTOM)), laneSegments(rows[0]))
        assertEquals(
            listOf(LaneSegment(0, 0, LaneY.TOP, 0, LaneY.MID), LaneSegment(0, 0, LaneY.MID, 0, LaneY.BOTTOM)),
            laneSegments(rows[1]),
        )
        assertEquals(listOf(LaneSegment(0, 0, LaneY.TOP, 0, LaneY.MID)), laneSegments(rows[2]))
    }

    @Test fun `a merge leaves its node on two lanes and the second parent's lane curves into its own commit`() {
        val rows = rows(commit("m", "a", "f"), commit("a", "r"), commit("f", "r"), commit("r"))
        val merge = laneSegments(rows[0])
        assertTrue(LaneSegment(1, 0, LaneY.MID, 1, LaneY.BOTTOM) in merge)
        // the feature lane runs past the main commit untouched
        assertTrue(LaneSegment(1, 1, LaneY.TOP, 1, LaneY.BOTTOM) in laneSegments(rows[1]))
        // where both meet the root, the feature lane bends into the node on lane 0
        val root = laneSegments(rows[3])
        assertTrue(LaneSegment(0, 0, LaneY.TOP, 0, LaneY.MID) in root)
        assertTrue(LaneSegment(1, 1, LaneY.TOP, 0, LaneY.MID) in root)
    }

    @Test fun `lanes past the drawn width fold into the last column`() {
        assertEquals(7, foldLane(7, 8))
        assertEquals(7, foldLane(12, 8))
    }

    private fun ref(name: String, kind: GitRefKind, current: Boolean = false) = GitRef(name, kind, current)

    @Test fun `a branch and its remote twin on one commit are one synced chip`() {
        val chips = chipModels(listOf(ref("main", GitRefKind.LOCAL, true), ref("origin/main", GitRefKind.REMOTE), ref("v1", GitRefKind.TAG)))
        assertEquals(listOf("main", "v1"), chips.map { it.name })
        assertTrue(chips[0].synced && chips[0].current)
    }

    @Test fun `a remote branch with no local twin keeps its own chip and tags come last`() {
        val chips = chipModels(listOf(ref("v2", GitRefKind.TAG), ref("origin/dev", GitRefKind.REMOTE), ref("feature", GitRefKind.LOCAL)))
        assertEquals(listOf("feature", "origin/dev", "v2"), chips.map { it.name })
        assertTrue(chips.none { it.synced })
    }

    @Test fun `chips beyond the shown count fold into a number`() {
        val chips = List(5) { RefChipModel("b$it", GitRefKind.LOCAL, false, false) }
        val (shown, more) = visibleChips(chips, shown = 2)
        assertEquals(2, shown.size)
        assertEquals(3, more)
        assertEquals(0, visibleChips(chips.take(2), shown = 2).second)
    }

    @Test fun `chip text takes whichever of the two colours contrasts more`() {
        val dark = Color(0xFF101010)
        val light = Color(0xFFF0F0F0)
        assertEquals(dark, readableOn(Color(0xFF80C0FF), dark, light))
        assertEquals(light, readableOn(Color(0xFF104080), dark, light))
    }

    private fun change(path: String) = GitChange(path, GitChangeType.MODIFIED, false)

    @Test fun `the tree puts folders before files and joins a chain of single folders`() {
        val items = flattenChanges(listOf(change("a/b/c/x.kt"), change("a/b/c/y.kt"), change("top.kt"), change("docs/r.md")), emptySet())
        assertEquals(
            listOf("a/b/c" to 0, "x.kt" to 1, "y.kt" to 1, "docs" to 0, "r.md" to 1, "top.kt" to 0),
            items.map { (if (it is ChangeItem.Folder) it.label else (it as ChangeItem.File).change.name) to it.level },
        )
    }

    @Test fun `a collapsed folder hides its files and the collapse key is the joined path`() {
        val items = flattenChanges(listOf(change("a/b/x.kt"), change("a/b/y.kt"), change("z.kt")), setOf("a/b"))
        assertEquals(listOf("a/b", "z.kt"), items.map { if (it is ChangeItem.Folder) it.label else (it as ChangeItem.File).change.name })
        assertEquals("a/b", (items[0] as ChangeItem.Folder).path)
    }

    @Test fun `a folder with files of its own is not joined with its subfolder`() {
        val items = flattenChanges(listOf(change("a/x.kt"), change("a/b/y.kt")), emptySet())
        assertEquals(listOf("a", "b", "y.kt", "x.kt"), items.map { if (it is ChangeItem.Folder) it.label else (it as ChangeItem.File).change.name })
        assertEquals("a/b", (items[1] as ChangeItem.Folder).path)
    }

    @Test fun `no changes give no rows`() {
        assertTrue(flattenChanges(emptyList(), emptySet()).isEmpty())
    }
}
