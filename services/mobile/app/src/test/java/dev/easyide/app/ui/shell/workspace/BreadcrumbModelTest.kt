package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.ui.screens.workspace.lsp.NavLocation
import dev.easyide.app.ui.screens.workspace.lsp.SymbolRow
import dev.easyide.lsp.protocol.Position
import dev.easyide.lsp.protocol.Range
import dev.easyide.lsp.protocol.SymbolKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreadcrumbModelTest {
    @Test fun `the path has a crumb per folder and the file, not the root`() {
        val crumbs = BreadcrumbModel.path("src/main/App.kt")
        assertEquals(listOf("src", "main", "App.kt"), crumbs.map { it.name })
        assertEquals(listOf("", "src", "src/main"), crumbs.map { it.parent })
        assertEquals(listOf(false, false, true), crumbs.map { it.isFile })
        assertEquals("src/main", crumbs[1].path)
    }

    @Test fun `a file in the root is a single crumb whose siblings are the root`() {
        val crumb = BreadcrumbModel.path("README.md").single()
        assertEquals("", crumb.parent)
        assertTrue(crumb.isFile)
    }

    @Test fun `children list the folder's direct entries, folders first`() {
        val index = listOf("b.txt", "src/main/App.kt", "src/util.kt", "a.txt", "src/main/Other.kt", "Zed/x.kt")
        val root = BreadcrumbModel.children(index, "")
        assertEquals(listOf("src", "Zed", "a.txt", "b.txt"), root.map { it.name })
        assertEquals(listOf(true, true, false, false), root.map { it.isDir })
        val src = BreadcrumbModel.children(index, "src")
        assertEquals(listOf("src/main", "src/util.kt"), src.map { it.path })
    }

    @Test fun `a folder that is a prefix of another name does not match`() {
        assertEquals(emptyList<DirEntry>(), BreadcrumbModel.children(listOf("srcs/a.kt"), "src"))
    }

    @Test fun `symbols at the caret are the enclosing ones, outermost first`() {
        val text = "class A {\n  fun f() {\n    x\n  }\n  fun g() {}\n}\nval top = 1\n"
        val rows = listOf(row("A", 0, 0, 0, 5, 1), row("f", 1, 1, 6, 3, 3), row("g", 1, 4, 6, 4, 14))
        val inF = BreadcrumbModel.symbolsAt(rows, text, text.indexOf("x"))
        assertEquals(listOf("A", "f"), inF.map { it.name })
        assertEquals(listOf("A", "g"), BreadcrumbModel.symbolsAt(rows, text, text.indexOf("g()")).map { it.name })
        assertEquals(emptyList<SymbolRow>(), BreadcrumbModel.symbolsAt(rows, text, text.indexOf("top")))
    }

    @Test fun `no outline, or a row without a body, gives no symbols`() {
        assertEquals(emptyList<SymbolRow>(), BreadcrumbModel.symbolsAt(emptyList(), "abc", 1))
        val bodiless = row("w", 0, 0, 0, 0, 0).copy(span = null)
        assertEquals(emptyList<SymbolRow>(), BreadcrumbModel.symbolsAt(listOf(bodiless), "abc", 1))
    }

    @Test fun `positions count lines and clamp the offset`() {
        assertEquals(Position(0, 2), BreadcrumbModel.positionOf("abc\ndef", 2))
        assertEquals(Position(1, 1), BreadcrumbModel.positionOf("abc\ndef", 5))
        assertEquals(Position(1, 3), BreadcrumbModel.positionOf("abc\ndef", 99))
        assertEquals(Position(0, 0), BreadcrumbModel.positionOf("", 0))
    }

    private fun row(name: String, depth: Int, startLine: Int, startChar: Int, endLine: Int, endChar: Int) = SymbolRow(
        name, null, SymbolKind.FUNCTION, depth, NavLocation("f", File("f"), "f", Range(Position(startLine, startChar), Position(startLine, startChar + 1))),
        Range(Position(startLine, startChar), Position(endLine, endChar)),
    )
}
