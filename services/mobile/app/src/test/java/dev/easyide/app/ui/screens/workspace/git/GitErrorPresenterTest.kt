package dev.easyide.app.ui.screens.workspace.git

import dev.easyide.sandbox.git.GitFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GitErrorPresenterTest {

    @Test fun `every known failure has its own explanation`() {
        val known = GitFailureKind.entries - GitFailureKind.UNKNOWN
        val ids = known.map { GitErrorPresenter.explanation(it) }
        ids.forEach { assertNotNull(it) }
        assertEquals("explanations must not be shared between kinds", known.size, ids.toSet().size)
    }

    @Test fun `unknown failures show git's own words only`() {
        assertNull(GitErrorPresenter.explanation(GitFailureKind.UNKNOWN))
    }
}
