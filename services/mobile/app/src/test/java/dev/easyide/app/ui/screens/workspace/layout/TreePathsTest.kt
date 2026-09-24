package dev.easyide.app.ui.screens.workspace.layout

import dev.easyide.sandbox.files.FileNode
import org.junit.Assert.assertEquals
import org.junit.Test

class TreePathsTest {

    private fun file(path: String) = FileNode(path.substringAfterLast('/'), path, isDirectory = false, sizeBytes = 0)
    private fun dir(path: String) = FileNode(path.substringAfterLast('/'), path, isDirectory = true, sizeBytes = 0)

    @Test
    fun `ancestors are outermost first`() {
        assertEquals(listOf("a", "a/b"), TreePaths.ancestors("a/b/c.kt"))
        assertEquals(emptyList<String>(), TreePaths.ancestors("c.kt"))
    }

    private val roots = listOf(dir("src"), file("README.md"))
    private val children = mapOf(
        "src" to listOf(dir("src/main"), file("src/a.kt")),
        "src/main" to listOf(file("src/main/b.kt")),
    )

    @Test
    fun `index counts the rows drawn before the target`() {
        val expanded = setOf("src", "src/main")
        assertEquals(0, TreePaths.visibleIndex("src", roots, expanded, children))
        assertEquals(2, TreePaths.visibleIndex("src/main/b.kt", roots, expanded, children))
        assertEquals(3, TreePaths.visibleIndex("src/a.kt", roots, expanded, children))
        assertEquals(4, TreePaths.visibleIndex("README.md", roots, expanded, children))
    }

    @Test
    fun `collapsed and unknown paths are not visible`() {
        assertEquals(-1, TreePaths.visibleIndex("src/a.kt", roots, emptySet(), children))
        assertEquals(1, TreePaths.visibleIndex("README.md", roots, emptySet(), children))
        assertEquals(-1, TreePaths.visibleIndex("nope", roots, setOf("src"), children))
    }
}
