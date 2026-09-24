package dev.easyide.app.extensions

import dev.easyide.extensions.contrib.ContributionRef
import dev.easyide.extensions.contrib.IconThemeContribution
import dev.easyide.extensions.contrib.Owned
import dev.easyide.extensions.contrib.Owner
import dev.easyide.extensions.contrib.PackageFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveIconThemeTest {

    private fun contribution(id: String) = Owned(
        Owner.BuiltIn,
        ContributionRef(ContributionRef.Kind.ICON_THEME, null, id),
        "/contributes/iconThemes/0",
        IconThemeContribution(id, "Label $id", PackageFile("icons/$id.json", "/ext/icons/$id.json")),
    )

    @Test
    fun selectionLoadsTheMatchingEnabledThemeElseBuiltIn() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val themes = MutableStateFlow(listOf(contribution("seti"), contribution("minimal")))
        val selection = MutableStateFlow("")
        val warnings = ArrayList<String>()
        val reads = ArrayList<String>()
        val active = ActiveIconTheme(themes, selection, StandardTestDispatcher(testScheduler), scope, warn = { warnings += it }) { f ->
            reads += f.path
            if (f.name == "minimal.json") "not json" else """{"iconDefinitions": {}, "file": "_f"}"""
        }
        scope.advanceUntilIdle()
        assertNull(active.theme.value)
        assertEquals(listOf("seti", "minimal"), active.choices.value.map { it.id })

        selection.value = "seti"
        scope.advanceUntilIdle()
        assertEquals("seti", active.theme.value?.id)

        selection.value = "minimal"
        scope.advanceUntilIdle()
        assertNull("an unparseable file falls back to the built-in icons", active.theme.value)
        assertEquals(1, warnings.size)

        // Disabling the extension that provides the selected theme falls back too.
        selection.value = "seti"
        scope.advanceUntilIdle()
        themes.value = listOf(contribution("minimal"))
        scope.advanceUntilIdle()
        assertNull(active.theme.value)
        assertEquals(listOf("/ext/icons/seti.json", "/ext/icons/minimal.json", "/ext/icons/seti.json"), reads)
        scope.cancel()
    }
}
