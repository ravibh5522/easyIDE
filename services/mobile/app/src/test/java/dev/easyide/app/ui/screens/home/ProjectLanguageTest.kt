package dev.easyide.app.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectLanguageTest {

    @Test fun `marker file at the root wins`() {
        assertEquals(ProjectLanguage.RUST, ProjectLanguage.detect(listOf("Cargo.toml", "README.md")))
        assertEquals(ProjectLanguage.PYTHON, ProjectLanguage.detect(listOf("pyproject.toml", "main.js")))
    }

    @Test fun `typescript beats javascript and kotlin beats java`() {
        assertEquals(ProjectLanguage.TYPESCRIPT, ProjectLanguage.detect(listOf("package.json", "tsconfig.json")))
        assertEquals(ProjectLanguage.KOTLIN, ProjectLanguage.detect(listOf("build.gradle.kts", "build.gradle")))
        assertEquals(ProjectLanguage.JAVASCRIPT, ProjectLanguage.detect(listOf("package.json")))
    }

    @Test fun `without a marker the most common extension wins`() {
        val root = listOf("a.py", "b.py", "c.js", "README.md")
        assertEquals(ProjectLanguage.PYTHON, ProjectLanguage.detect(root))
        assertEquals(ProjectLanguage.GO, ProjectLanguage.detect(listOf("a.py"), listOf("x.go", "y.go", "z.go")))
    }

    @Test fun `a root file outweighs one under src`() {
        assertEquals(ProjectLanguage.PYTHON, ProjectLanguage.detect(listOf("main.py", "README.md"), listOf("app.js")))
    }

    @Test fun `ties resolve in declaration order`() {
        assertEquals(ProjectLanguage.KOTLIN, ProjectLanguage.detect(listOf("a.kt", "b.py")))
    }

    @Test fun `nothing recognisable gives null`() {
        assertNull(ProjectLanguage.detect(listOf("README.md", "notes.txt", "noextension")))
        assertNull(ProjectLanguage.detect(emptyList()))
    }

    @Test fun `every tint indexes a lane colour`() {
        ProjectLanguage.entries.forEach { assertEquals(true, it.tint in 0 until 8) }
    }
}
