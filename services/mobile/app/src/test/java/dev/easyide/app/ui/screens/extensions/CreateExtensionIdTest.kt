package dev.easyide.app.ui.screens.extensions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateExtensionIdTest {

    @Test fun `publisher and name are folded to lower case and trimmed before the id is checked`() {
        assertTrue(validExtensionId(" Acme ", "Docker-Tools"))
    }

    @Test fun `empty parts and characters outside letters, digits and dash are refused`() {
        assertFalse(validExtensionId("", "docker"))
        assertFalse(validExtensionId("acme", ""))
        assertFalse(validExtensionId("acme", "dock er"))
        assertFalse(validExtensionId("ac.me", "docker"))
    }
}
