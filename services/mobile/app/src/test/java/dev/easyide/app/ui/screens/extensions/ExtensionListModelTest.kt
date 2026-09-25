package dev.easyide.app.ui.screens.extensions

import dev.easyide.extensions.host.ActivationState
import dev.easyide.extensions.host.DisabledReason
import dev.easyide.extensions.host.InstalledPackage
import dev.easyide.extensions.host.PackageProblem
import dev.easyide.extensions.manifest.InstallScope
import dev.easyide.extensions.manifest.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExtensionListModelTest {

    private fun tags(
        invalid: Boolean = false,
        reason: DisabledReason? = null,
        activation: ActivationState? = ActivationState.ACTIVE,
        revoked: Boolean = false,
        update: Boolean = false,
    ) = rowTags(invalid, reason, activation, revoked, update)

    private fun item(id: String, name: String = id, description: String? = null, source: Source = Source.SIDELOAD, tags: List<RowTag> = emptyList()) =
        ExtensionListItem(id, id, name, "1.0.0", description, source, toggleable = true, enabled = true, tags = tags)

    @Test fun `a healthy pack carries no state tag`() {
        assertEquals(emptyList<RowTag>(), tags())
    }

    @Test fun `a user-disabled pack is tagged disabled and an approval gap needs approval`() {
        assertEquals(listOf(RowTag.Disabled), tags(reason = DisabledReason.USER_DISABLED, activation = null))
        assertEquals(listOf(RowTag.NeedsApproval), tags(reason = DisabledReason.NEEDS_APPROVAL, activation = null))
    }

    @Test fun `crashes show as crashed whether the pack was disabled for it or is still enabled`() {
        assertEquals(listOf(RowTag.Crashed), tags(reason = DisabledReason.CRASH_DISABLED, activation = ActivationState.CRASH_DISABLED))
        assertEquals(listOf(RowTag.Crashed), tags(activation = ActivationState.CRASHED))
        assertEquals(listOf(RowTag.Failed), tags(activation = ActivationState.FAILED))
    }

    @Test fun `revoked by the registry or by enablement both read revoked`() {
        assertEquals(listOf(RowTag.Revoked), tags(revoked = true))
        assertEquals(listOf(RowTag.Revoked), tags(reason = DisabledReason.REVOKED, activation = null))
    }

    @Test fun `tags are ordered by urgency and capped so a row stays readable`() {
        val all = tags(invalid = true, revoked = true, reason = DisabledReason.USER_DISABLED, update = true)
        assertEquals(listOf(RowTag.Invalid, RowTag.Revoked), all)
        assertEquals(listOf(RowTag.Disabled, RowTag.Update), tags(reason = DisabledReason.USER_DISABLED, update = true))
    }

    @Test fun `filter needs every word in the id, name or description and ignores case`() {
        val items = listOf(
            item("acme.docker", "Docker controller", "Start and stop containers"),
            item("easyide.python", "Python", "Python language server"),
        )
        assertEquals(items, filterItems(items, "  "))
        assertEquals(listOf("acme.docker"), filterItems(items, "DOCKER").map { it.id })
        assertEquals(listOf("acme.docker"), filterItems(items, "stop containers").map { it.id })
        assertEquals(listOf("easyide.python"), filterItems(items, "language SERVER").map { it.id })
        assertEquals(emptyList<ExtensionListItem>(), filterItems(items, "docker python"))
    }

    @Test fun `groups put what the user installed before what ships with the app and keep order inside`() {
        val groups = groupItems(listOf(item("a.one", source = Source.BUILT_IN), item("b.two"), item("c.three", source = Source.REGISTRY)))
        assertEquals(listOf("b.two", "c.three"), groups.installed.map { it.id })
        assertEquals(listOf("a.one"), groups.builtIn.map { it.id })
        assertFalse(groups.isEmpty)
        assertTrue(groupItems(emptyList()).isEmpty)
    }

    @Test fun `attention lists packs waiting on approval and revoked ones`() {
        val items = listOf(item("a.one", tags = listOf(RowTag.NeedsApproval)), item("b.two"), item("c.three", tags = listOf(RowTag.Revoked, RowTag.Update)))
        val a = attention(items)
        assertEquals(listOf("a.one"), a.needsApproval.map { it.id })
        assertEquals(listOf("c.three"), a.revoked.map { it.id })
        assertEquals(Attention(emptyList(), emptyList()), attention(listOf(item("b.two"))))
    }

    @Test fun `an invalid package lists by its directory names with no toggle`() {
        val dir = File("/files/extensions/global/acme.broken/1.2.3")
        val pkg = InstalledPackage(dir, InstallScope.GLOBAL, null, Source.SIDELOAD, 1L, emptySet(), revoked = false, crashDisabled = false)
        val row = ExtensionRow(pkg, null, PackageProblem(pkg, emptyList(), emptyList()), null, null, userEnabled = true, contributions = emptyList(), shadowed = emptyList())
        val item = row.toItem(updateTo = null, revokedReason = null)
        assertEquals("acme.broken", item.id)
        assertEquals("1.2.3", item.version)
        assertFalse(item.toggleable)
        assertEquals(listOf(RowTag.Invalid), item.tags)
    }

    @Test fun `an update badge is only for registry installs`() {
        val dir = File("/files/extensions/global/acme.tool/1.0.0")
        fun row(source: Source) = InstalledPackage(dir, InstallScope.GLOBAL, null, source, 1L, emptySet(), revoked = false, crashDisabled = false)
            .let { ExtensionRow(it, null, PackageProblem(it, emptyList(), emptyList()), null, null, true, emptyList(), emptyList()) }
        assertTrue(RowTag.Update in row(Source.REGISTRY).toItem("1.1.0", null).tags)
        assertFalse(RowTag.Update in row(Source.SIDELOAD).toItem("1.1.0", null).tags)
    }
}
