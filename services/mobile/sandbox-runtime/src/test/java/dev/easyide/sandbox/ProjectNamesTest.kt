package dev.easyide.sandbox

import dev.easyide.sandbox.ProjectNames.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectNamesTest {

    @Test fun `blank names are rejected`() {
        assertEquals(Problem.BLANK, ProjectNames.problem("", emptyList()))
        assertEquals(Problem.BLANK, ProjectNames.problem("   ", listOf("a")))
    }

    @Test fun `duplicates ignore case and surrounding whitespace`() {
        assertEquals(Problem.DUPLICATE, ProjectNames.problem(" My-App ", listOf("my-app")))
        assertNull(ProjectNames.problem("my-app-2", listOf("my-app")))
    }

    @Test fun `unique keeps a free name and numbers a taken one`() {
        assertEquals("api", ProjectNames.unique("api", listOf("web")))
        assertEquals("api 2", ProjectNames.unique("api", listOf("API")))
        assertEquals("api 3", ProjectNames.unique("api", listOf("api", "api 2")))
    }
}
