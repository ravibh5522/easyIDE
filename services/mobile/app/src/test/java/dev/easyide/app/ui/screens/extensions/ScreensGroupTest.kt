package dev.easyide.app.ui.screens.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Extensions page lists what a pack adds to the shell, so nothing it contributes is hidden (extension-ui.md section 3). */
class ScreensGroupTest {
    @Test fun `navigation, documents, openers, presets and badges are one group`() {
        listOf("navigation:acme.docker.nav", "viewBadge:acme.docker.nav", "document:acme.docker/container", "documentOpener:**/*.yml=>acme.docker/c", "layoutPreset:acme.docker.ops")
            .forEach { assertEquals(it, ContributionGroup.Screens, groupOf(it)) }
    }

    @Test fun `the group sits before appearance and after views`() {
        val order = ContributionGroup.entries
        assertEquals(order.indexOf(ContributionGroup.Views) + 1, order.indexOf(ContributionGroup.Screens))
        assertEquals(order.indexOf(ContributionGroup.Screens) + 1, order.indexOf(ContributionGroup.Appearance))
    }
}
