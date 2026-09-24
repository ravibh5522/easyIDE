package dev.easyide.app.ui.screens.workspace.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RecentFilesTest {

    @Test
    fun `touching moves a file to the front without duplicating it`() {
        val r = RecentFiles().touched("a").touched("b").touched("c").touched("a")
        assertEquals(listOf("a", "c", "b"), r.paths)
    }

    @Test
    fun `the list is capped`() {
        var r = RecentFiles()
        repeat(RecentFiles.MAX + 10) { r = r.touched("f$it") }
        assertEquals(RecentFiles.MAX, r.paths.size)
        assertEquals("f${RecentFiles.MAX + 9}", r.paths.first())
    }

    @Test
    fun `removing a directory removes everything under it`() {
        val r = RecentFiles(listOf("src/a.kt", "src2/b.kt", "src/deep/c.kt", "src"))
        assertEquals(listOf("src2/b.kt"), r.without("src").paths)
        assertSame(r, r.without("nothing"))
    }

    @Test
    fun `renaming a file or directory rewrites paths in place`() {
        val r = RecentFiles(listOf("src/a.kt", "src2/b.kt", "src/deep/c.kt"))
        assertEquals(listOf("lib/a.kt", "src2/b.kt", "lib/deep/c.kt"), r.renamed("src", "lib").paths)
        assertEquals(listOf("src/x.kt", "src2/b.kt", "src/deep/c.kt"), r.renamed("src/a.kt", "src/x.kt").paths)
    }
}
