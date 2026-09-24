package dev.easyide.app.ui.screens.settings

import dev.easyide.sandbox.model.SandboxEnvironment
import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.SandboxBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLayerModelTest {

    private fun env(id: String) =
        EnvironmentListItem(SandboxEnvironment(id, id, SandboxBackend.entries.first(), EnvironmentState.READY, 0L, 0L), 0)
    private val envs = listOf(env("e1"), env("e2"))
    private val projects = listOf(ProjectChoice("p1", "one", "e1"), ProjectChoice("p2", "two", "e2"))

    @Test fun `segments follow the tab`() {
        assertEquals(SettingsLayerModel.USER, SettingsLayerModel.segmentOf(LayerTab.User))
        assertEquals(SettingsLayerModel.ENVIRONMENT, SettingsLayerModel.segmentOf(LayerTab.Environment("e1")))
        assertEquals(SettingsLayerModel.PROJECT, SettingsLayerModel.segmentOf(LayerTab.Project("p1", "e1")))
    }

    @Test fun `user is always available, the others need something to pick`() {
        assertTrue(SettingsLayerModel.isAvailable(SettingsLayerModel.USER, emptyList(), emptyList()))
        assertFalse(SettingsLayerModel.isAvailable(SettingsLayerModel.ENVIRONMENT, emptyList(), projects))
        assertFalse(SettingsLayerModel.isAvailable(SettingsLayerModel.PROJECT, envs, emptyList()))
        assertTrue(SettingsLayerModel.isAvailable(SettingsLayerModel.ENVIRONMENT, envs, emptyList()))
    }

    @Test fun `selecting a segment picks the first target, or keeps the current one`() {
        assertEquals(LayerTab.Environment("e1"), SettingsLayerModel.select(SettingsLayerModel.ENVIRONMENT, LayerTab.User, envs, projects))
        assertEquals(LayerTab.Project("p1", "e1"), SettingsLayerModel.select(SettingsLayerModel.PROJECT, LayerTab.User, envs, projects))
        val current = LayerTab.Environment("e2")
        assertEquals(current, SettingsLayerModel.select(SettingsLayerModel.ENVIRONMENT, current, envs, projects))
        assertEquals(LayerTab.User, SettingsLayerModel.select(SettingsLayerModel.USER, current, envs, projects))
    }

    @Test fun `a segment with nothing to pick selects nothing`() {
        assertNull(SettingsLayerModel.select(SettingsLayerModel.ENVIRONMENT, LayerTab.User, emptyList(), projects))
        assertNull(SettingsLayerModel.select(SettingsLayerModel.PROJECT, LayerTab.User, envs, emptyList()))
        assertNull(SettingsLayerModel.select(99, LayerTab.User, envs, projects))
    }
}
