package dev.easyide.app.ui.screens.workspace

import dev.easyide.sandbox.files.FileNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExplorerTargetTest {
    private fun dir(path: String) = FileNode(path.substringAfterLast("/"), path, true, 0)
    private fun file(path: String) = FileNode(path.substringAfterLast("/"), path, false, 0)

    private val src = dir("src")
    private val deep = dir("src/main")
    private val state = WorkspaceUiState(
        tree = listOf(src, file("README.md")),
        childrenByDir = mapOf("src" to listOf(deep, file("src/app.js")), "src/main" to listOf(file("src/main/A.kt"))),
    )

    @Test fun `a tapped folder is the target`() = assertEquals(deep, explorerTarget(state, deep))

    @Test fun `a tapped file targets its folder`() = assertEquals(deep, explorerTarget(state, file("src/main/A.kt")))

    @Test fun `a root file targets the project root`() = assertNull(explorerTarget(state, file("README.md")))

    @Test fun `with nothing tapped the open file's folder is used`() =
        assertEquals(src, explorerTarget(state.copy(activeTabPath = "src/app.js"), null))

    @Test fun `with nothing tapped and no file open it is the root`() = assertNull(explorerTarget(state, null))
}
