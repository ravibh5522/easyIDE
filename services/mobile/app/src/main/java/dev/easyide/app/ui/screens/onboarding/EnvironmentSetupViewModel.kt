package dev.easyide.app.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.easyide.app.data.SandboxImages
import dev.easyide.sandbox.EnvironmentManager
import dev.easyide.sandbox.LinuxEnvironment
import dev.easyide.sandbox.bootstrap.InstallEvent
import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.SandboxBackend
import dev.easyide.sandbox.model.SandboxImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException

sealed interface SetupStage {
    data object Choosing : SetupStage

    /** [event] is the latest structured progress; [fraction] the overall bar, null while unknowable. */
    data class Installing(
        val imageLabel: String,
        val event: InstallEvent?,
        val fraction: Float?,
        val lastLine: String?,
    ) : SetupStage

    data class Failed(val imageLabel: String, val failure: ClassifiedFailure) : SetupStage

    /** [imageLabel] is null when Linux was already installed before this screen opened. */
    data class Ready(val imageLabel: String?) : SetupStage
}

data class EnvironmentSetupState(
    val images: List<SandboxImage>,
    val selectedImageId: String,
    val stage: SetupStage = SetupStage.Choosing,
)

/**
 * Installs the first Linux environment: pick a preset, download and unpack it
 * with real progress, and cancel, retry or resume when it does not finish.
 *
 * A retry, and a fresh attempt after cancelling or after the app was killed
 * mid-install, reuses the environment record the earlier attempt left behind
 * rather than piling up half-installed ones, and the downloader resumes from
 * the bytes it already has.
 */
class EnvironmentSetupViewModel(
    private val environmentManager: EnvironmentManager,
    private val linuxEnvironment: LinuxEnvironment,
    private val keepAlive: InstallKeepAlive,
    images: List<SandboxImage> = SandboxImages.CATALOG,
) : ViewModel() {

    private val _state = MutableStateFlow(EnvironmentSetupState(images, images.first().id))
    val state: StateFlow<EnvironmentSetupState> = _state.asStateFlow()

    private var installJob: Job? = null

    init {
        viewModelScope.launch {
            val installed = environmentManager.environments.first().any { it.state == EnvironmentState.READY }
            if (installed) _state.update { it.copy(stage = SetupStage.Ready(imageLabel = null)) }
        }
    }

    fun onImageSelected(imageId: String) = _state.update { it.copy(selectedImageId = imageId) }

    /** Also the retry: from [SetupStage.Failed] this runs the same preset again. */
    fun install() {
        if (installJob?.isActive == true) return
        val image = _state.value.let { s -> s.images.first { it.id == s.selectedImageId } }
        installJob = viewModelScope.launch {
            setStage(SetupStage.Installing(image.label, event = null, fraction = null, lastLine = null))
            keepAlive.start()
            try {
                runInstall(image)
            } finally {
                keepAlive.stop()
            }
        }
    }

    /** Stops the install. A partial download is kept, so installing again resumes it. */
    fun cancel() {
        installJob?.cancel()
        _state.update { if (it.stage is SetupStage.Installing) it.copy(stage = SetupStage.Choosing) else it }
    }

    /** Back to choosing a preset after a failure. */
    fun backToChoosing() = setStage(SetupStage.Choosing)

    private suspend fun runInstall(image: SandboxImage) {
        val hasSetup = image.setupCommands.isNotEmpty()
        val environment = environmentManager.environments.first()
            .firstOrNull { it.state != EnvironmentState.READY && it.imageId == image.id }
            ?: environmentManager.create(image.label, SandboxBackend.PROOT, image.id).getOrElse {
                return setStage(SetupStage.Failed(image.label, classifyInstallFailure(it)))
            }

        val error = linuxEnvironment.install(
            environmentId = environment.id,
            image = image,
            onProgress = { line -> onInstalling { it.copy(lastLine = line) } },
            onEvent = { event ->
                onInstalling { it.copy(event = event, fraction = nextFraction(it.fraction, event, hasSetup)) }
            },
        ).exceptionOrNull()

        when {
            error == null -> {
                environmentManager.markProvisioned(environment.id)
                setStage(SetupStage.Ready(image.label))
            }
            // The user cancelled: cancel() already returned the screen to Choosing.
            error is CancellationException -> Unit
            else -> {
                environmentManager.markProvisioned(environment.id, error.message ?: error::class.java.simpleName)
                setStage(SetupStage.Failed(image.label, classifyInstallFailure(error)))
            }
        }
    }

    /** Applies [change] only while installing, so a late event after cancel cannot resurrect the progress view. */
    private fun onInstalling(change: (SetupStage.Installing) -> SetupStage.Installing) =
        _state.update { s -> (s.stage as? SetupStage.Installing)?.let { s.copy(stage = change(it)) } ?: s }

    private fun setStage(stage: SetupStage) = _state.update { it.copy(stage = stage) }
}
