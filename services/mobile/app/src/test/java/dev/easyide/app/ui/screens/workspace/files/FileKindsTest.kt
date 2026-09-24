package dev.easyide.app.ui.screens.workspace.files

import org.junit.Assert.assertEquals
import org.junit.Test

class FileKindsTest {

    @Test
    fun `extensions map to kinds case-insensitively`() {
        assertEquals(FileKind.PYTHON, FileKinds.of("main.py"))
        assertEquals(FileKind.PYTHON, FileKinds.of("MAIN.PY"))
        assertEquals(FileKind.JAVASCRIPT, FileKinds.of("app.jsx"))
        assertEquals(FileKind.TYPESCRIPT, FileKinds.of("app.d.ts"))
        assertEquals(FileKind.JVM, FileKinds.of("Main.kt"))
        assertEquals(FileKind.MARKDOWN, FileKinds.of("README.md"))
        assertEquals(FileKind.IMAGE, FileKinds.of("logo.PNG"))
    }

    @Test
    fun `whole names win over extensions`() {
        assertEquals(FileKind.LOCK, FileKinds.of("package-lock.json"))
        assertEquals(FileKind.BUILD, FileKinds.of("package.json"))
        assertEquals(FileKind.JSON, FileKinds.of("data.json"))
        assertEquals(FileKind.CONTAINER, FileKinds.of("Dockerfile"))
        assertEquals(FileKind.BUILD, FileKinds.of("Makefile"))
        assertEquals(FileKind.GIT, FileKinds.of(".gitignore"))
        assertEquals(FileKind.LICENSE, FileKinds.of("LICENSE"))
    }

    @Test
    fun `environment files and dotfiles`() {
        assertEquals(FileKind.ENVIRONMENT, FileKinds.of(".env"))
        assertEquals(FileKind.ENVIRONMENT, FileKinds.of(".env.local"))
        assertEquals(FileKind.DOTFILE, FileKinds.of(".bashrc"))
        assertEquals(FileKind.DOTFILE, FileKinds.of(".editorconfig"))
        assertEquals(FileKind.JSON, FileKinds.of(".eslintrc.json"))
    }

    @Test
    fun `unknown names fall back to the default`() {
        assertEquals(FileKind.DEFAULT, FileKinds.of("notes"))
        assertEquals(FileKind.DEFAULT, FileKinds.of("weird.zzz"))
        assertEquals(FileKind.DEFAULT, FileKinds.of("trailingdot."))
        assertEquals(FileKind.DEFAULT, FileKinds.of(""))
    }
}
