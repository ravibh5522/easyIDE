package dev.easyide.app.ui.screens.workspace

import dev.easyide.sandbox.files.FileNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileContextMenuTest {
    private val file = FileNode("a.kt", "src/a.kt", isDirectory = false, sizeBytes = 1)
    private val folder = FileNode("src", "src", isDirectory = true, sizeBytes = 0)

    @Test fun `a file needs no typed name`() {
        assertTrue(deleteConfirmed(file, ""))
    }

    @Test fun `a folder needs its exact name typed`() {
        assertFalse(deleteConfirmed(folder, ""))
        assertFalse(deleteConfirmed(folder, "sr"))
        assertTrue(deleteConfirmed(folder, "src"))
        assertTrue(deleteConfirmed(folder, "  src "))
    }
}
