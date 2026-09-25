package dev.easyide.app.ui.screens.workspace

import dev.easyide.sandbox.git.GitChangeType
import org.junit.Assert.assertEquals
import org.junit.Test

class TreeKeysTest {
    @Test fun `folders holding a change are marked all the way up`() {
        val marks = TreeMarks(emptySet(), mapOf("a/b/c.kt" to GitChangeType.MODIFIED, "top.kt" to GitChangeType.ADDED, "a/d.kt" to GitChangeType.UNTRACKED))
        assertEquals(setOf("a", "a/b"), marks.changedDirs)
    }

    @Test fun `no changes mark no folders`() {
        assertEquals(emptySet<String>(), TreeMarks(emptySet(), emptyMap()).changedDirs)
    }
}
