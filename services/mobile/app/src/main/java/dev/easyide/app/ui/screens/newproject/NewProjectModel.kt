package dev.easyide.app.ui.screens.newproject

import dev.easyide.app.data.SandboxImages
import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.SandboxEnvironment

/**
 * A preset is "ready" when an installed environment already came from it, so choosing it for a new
 * environment reuses cached downloads and starts fast. An environment that predates the catalog has
 * no image id and was made from the base image.
 */
internal fun presetReady(
    environments: List<SandboxEnvironment>,
    imageId: String,
    baseImageId: String = SandboxImages.DEFAULT.id,
): Boolean = environments.any { (it.imageId ?: baseImageId) == imageId && it.state == EnvironmentState.READY }

/** Name problems belong under the name field; everything else is a message above the Create button. */
internal fun NewProjectError.isAboutName(): Boolean = this == NewProjectError.NameTaken || this == NewProjectError.NameBlank
