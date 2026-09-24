package dev.easyide.app.ui.shell.diff

import dev.easyide.app.ui.screens.workspace.layout.PaneArrangement
import dev.easyide.app.ui.shell.DocumentRegistry
import dev.easyide.app.ui.shell.DocumentType
import dev.easyide.app.ui.shell.EditorGroup
import dev.easyide.app.ui.shell.EditorStage
import dev.easyide.app.ui.shell.Origin
import dev.easyide.app.ui.shell.ScopeState
import dev.easyide.app.ui.shell.ShellSnapshot
import dev.easyide.app.ui.shell.file
import dev.easyide.app.ui.shell.host.AppDocuments
import dev.easyide.app.ui.shell.uri
import dev.easyide.app.ui.shell.workspace.forWorkspace
import dev.easyide.sandbox.git.DiffEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitDocumentsTest {
    private val registry: DocumentRegistry = AppDocuments.registries { "t" }.forWorkspace().documents
    private val diff = requireNotNull(Comparison.unstaged("src/a.kt").uri)
    private val commit = requireNotNull(GitDocuments.commitUri("a".repeat(40)))

    @Test fun `the workspace registers both git types next to files`() {
        assertEquals(GitDocuments.DIFF_TYPE, registry.resolve(diff).id)
        assertEquals(GitDocuments.COMMIT_TYPE, registry.resolve(commit).id)
        assertEquals("easyide.file", registry.resolve(file("a.kt")).id)
    }

    @Test fun `both git types restore from their uri and a diff can be split off`() {
        for (type in listOf(GitDocuments.diffType, GitDocuments.commitType)) {
            assertTrue(type.restorable)
            assertTrue(type.supportsSplit)
            assertFalse(type.multiple)
        }
    }

    @Test fun `the fallback titles are the file and the short commit id`() {
        assertEquals("a.kt", registry.resolve(diff).title(diff))
        assertEquals("aaaaaaa", registry.resolve(commit).title(commit))
    }

    @Test fun `a full commit id is shortened and a name is left alone`() {
        assertEquals("abcdef0", GitDocuments.shortId("abcdef0123456789abcdef0123456789abcdef01"))
        assertEquals("main", GitDocuments.shortId("main"))
        assertEquals("abc1234", GitDocuments.shortId("abc1234"))
    }

    @Test fun `only a git-commit uri names a commit`() {
        assertEquals("a".repeat(40), GitDocuments.commitOf(commit))
        assertNull(GitDocuments.commitOf(diff))
        assertNull(GitDocuments.commitOf(null))
    }

    @Test fun `a registered type cannot be registered twice`() {
        val again = registry.register(GitDocuments.diffType, Origin.Core)
        assertEquals(1, again.rejections.size)
    }

    private fun stage(): ScopeState {
        val left = EditorGroup().open(file("a.kt"), preview = false).open(diff, preview = true)
        val right = EditorGroup().open(commit, preview = false)
        return ScopeState.workspace(PaneArrangement.FULL).copy(stage = EditorStage(listOf(left, right), 1))
    }

    @Test fun `the git documents survive a snapshot round trip in their groups`() {
        val json = ShellSnapshot.encodeWorkspace(stage(), PaneArrangement.FULL, registry::resolve)
        val restored = requireNotNull(ShellSnapshot.decodeWorkspace(json, PaneArrangement.FULL)).state.stage
        assertEquals(2, restored.groups.size)
        assertEquals(1, restored.active)
        // The file document is restored from disk, not the snapshot; the git documents restore from their uris.
        assertEquals(listOf(diff), restored.groups[0].tabs.map { it.uri })
        assertEquals(listOf(commit), restored.groups[1].tabs.map { it.uri })
    }

    @Test fun `a restored comparison still names the same two ends`() {
        val json = ShellSnapshot.encodeWorkspace(stage(), PaneArrangement.FULL, registry::resolve)
        val restored = requireNotNull(ShellSnapshot.decodeWorkspace(json, PaneArrangement.FULL)).state.stage.groups[0].tabs.single().uri
        assertEquals(Comparison("src/a.kt", DiffEnd.Index, DiffEnd.Worktree), Comparison.of(restored))
    }

    @Test fun `an app that lacks the types keeps the tabs as placeholders`() {
        val bare = AppDocuments.registries { "t" }.documents
        val json = ShellSnapshot.encodeWorkspace(stage(), PaneArrangement.FULL, registry::resolve)
        val restored = requireNotNull(ShellSnapshot.decodeWorkspace(json, PaneArrangement.FULL)).state.stage
        assertEquals(DocumentType.UNAVAILABLE_ID, bare.resolve(restored.groups[0].tabs.single().uri).id)
        assertEquals(DocumentType.UNAVAILABLE_ID, bare.resolve(restored.groups[1].tabs.single().uri).id)
        assertEquals(diff, restored.groups[0].tabs.single().uri)
    }

    @Test fun `a snapshot with a malformed git uri drops that tab and keeps the rest`() {
        val json = ShellSnapshot.encodeWorkspace(stage(), PaneArrangement.FULL, registry::resolve)
            .replace("git-diff:///src/a.kt?base=index&head=worktree", "git-diff:///src/a.kt?base=%zz")
        val restored = requireNotNull(ShellSnapshot.decodeWorkspace(json, PaneArrangement.FULL)).state.stage
        assertEquals(listOf(commit), restored.documents)
    }

    @Test fun `git document uris are what the engine expects`() {
        assertEquals("git-commit", commit.scheme)
        assertEquals("git-diff", diff.scheme)
        assertEquals(uri("git-diff:///src/a.kt?head=worktree&base=index"), diff)
    }
}
