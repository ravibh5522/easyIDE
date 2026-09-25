package dev.easyide.app.ui.screens.newproject

import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.SandboxBackend
import dev.easyide.sandbox.model.SandboxEnvironment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewProjectModelTest {

    private fun env(imageId: String?, state: EnvironmentState) = SandboxEnvironment(
        id = "e-${imageId ?: "none"}-$state", label = "env", backend = SandboxBackend.PROOT, state = state,
        createdAtEpochMs = 0, lastUsedAtEpochMs = 0, imageId = imageId,
    )

    @Test fun `a preset is ready when an installed environment came from it`() {
        assertTrue(presetReady(listOf(env("python", EnvironmentState.READY)), "python", baseImageId = "base"))
    }

    @Test fun `an environment that is still installing or failed does not make its preset ready`() {
        val environments = listOf(env("python", EnvironmentState.PROVISIONING), env("python", EnvironmentState.FAILED))
        assertFalse(presetReady(environments, "python", baseImageId = "base"))
    }

    @Test fun `an environment from before the catalog counts for the base image only`() {
        val environments = listOf(env(null, EnvironmentState.READY))
        assertTrue(presetReady(environments, "base", baseImageId = "base"))
        assertFalse(presetReady(environments, "python", baseImageId = "base"))
    }

    @Test fun `no environments means no preset is ready`() {
        assertFalse(presetReady(emptyList(), "base", baseImageId = "base"))
    }

    @Test fun `only name errors belong under the name field`() {
        assertTrue(NewProjectError.NameTaken.isAboutName())
        assertTrue(NewProjectError.NameBlank.isAboutName())
        assertFalse(NewProjectError.FolderPickFailed.isAboutName())
        assertFalse(NewProjectError.Other("disk").isAboutName())
    }
}
