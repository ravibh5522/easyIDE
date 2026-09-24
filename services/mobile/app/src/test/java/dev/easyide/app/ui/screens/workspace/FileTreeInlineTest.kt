package dev.easyide.app.ui.screens.workspace

import dev.easyide.sandbox.files.FileNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileTreeInlineTest {
    private val node = FileNode("a.kt", "src/a.kt", isDirectory = false, sizeBytes = 1)

    @Test fun `a new file or folder appears among the children of its directory`() {
        assertEquals("", InlineEdit.NewFile("").parent)
        assertEquals("src", InlineEdit.NewFile("src").parent)
        assertEquals("src/ui", InlineEdit.NewFolder("src/ui").parent)
    }

    @Test fun `a rename replaces its own row instead of adding one`() {
        assertNull(InlineEdit.Rename(node).parent)
    }

    @Test fun `a rename starts from the current name and a creation from nothing`() {
        assertEquals("a.kt", InlineEdit.Rename(node).initial)
        assertEquals("", InlineEdit.NewFile("src").initial)
    }
}
