package dev.easyide.app.ui.screens.onboarding

import dev.easyide.app.data.SandboxImages
import dev.easyide.sandbox.EnvironmentManager
import dev.easyide.sandbox.LinuxEnvironment
import dev.easyide.sandbox.model.EnvironmentState
import dev.easyide.sandbox.model.SandboxBackend
import dev.easyide.sandbox.model.SandboxImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

/** The image whose install was started and not yet finished, kept across process death so it can be resumed. */
interface PendingInstallStore {
    suspend fun get(): String?
    suspend fun set(imageId: String?)
}

/**
 * Runs the first Linux install on the application's scope, not a screen's: leaving onboarding, rotating
 * or navigating away must not stop a download that takes minutes. [restore] runs at process start and,
 * if the process died mid-install (force close, Android reaping it), starts that install again - the
 * downloader resumes from the bytes it already has, and every attempt unpacks into an empty rootfs.
 *
 * A retry, and a fresh attempt after cancelling, reuses the environment record the earlier attempt left
 * behind rather than piling up half-installed ones.
 */
class BackgroundInstaller(
    private val environmentManager: EnvironmentManager,
    private val linuxEnvironment: LinuxEnvironment,
    private val keepAlive: InstallKeepAlive,
    private val pending: PendingInstallStore,
    private val scope: CoroutineScope,
) {
    private val _stage = MutableStateFlow<SetupStage>(SetupStage.Choosing)
    val stage: StateFlow<SetupStage> = _stage.asStateFlow()

    private var job: Job? = null

    /** Marks Linux as ready if it already is, otherwise resumes an install the last process did not finish. */
    fun restore() {
        scope.launch {
            val installed = environmentManager.environments.first().any { it.state == EnvironmentState.READY }
            if (installed) {
                pending.set(null)
                _stage.update { if (it is SetupStage.Choosing) SetupStage.Ready(imageLabel = null) else it }
                return@launch
            }
            pending.get()?.let { start(SandboxImages.byId(it)) }
        }
    }

    /** Also the retry: from [SetupStage.Failed] this runs the same preset again. */
    fun start(image: SandboxImage) {
        if (job?.isActive == true) return
        // A cancelled install is no longer active but may still be unwinding (deleting its rootfs,
        // stopping the keep-alive): wait for it, or its late stop() would end this install's keep-alive.
        val previous = job
        job = scope.launch {
            _stage.value = SetupStage.Installing(image.label, event = null, fraction = null, lastLine = null)
            previous?.join()
            try {
                pending.set(image.id)
                keepAlive.start()
                runInstall(image)
            } finally {
                keepAlive.stop()
                // Cleared on every way out, cancel included; only a killed process leaves it set.
                withContext(NonCancellable) { pending.set(null) }
            }
        }
    }

    /** Stops the install. A partial download is kept, so installing again resumes it. */
    fun cancel() {
        job?.cancel()
        _stage.update { if (it is SetupStage.Installing) SetupStage.Choosing else it }
    }

    /** Back to choosing a preset after a failure. */
    fun reset() = _stage.update { if (it is SetupStage.Failed) SetupStage.Choosing else it }

    private suspend fun runInstall(image: SandboxImage) {
        val hasSetup = image.setupCommands.isNotEmpty()
        val environment = environmentManager.environments.first()
            .firstOrNull { it.state != EnvironmentState.READY && it.imageId == image.id }
            ?: environmentManager.create(image.label, SandboxBackend.PROOT, image.id).getOrElse {
                return _stage.update { _ -> SetupStage.Failed(image.label, classifyInstallFailure(it)) }
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
                _stage.value = SetupStage.Ready(image.label)
            }
            // The user cancelled: cancel() already returned the screen to Choosing.
            error is CancellationException -> Unit
            else -> {
                environmentManager.markProvisioned(environment.id, error.message ?: error::class.java.simpleName)
                _stage.value = SetupStage.Failed(image.label, classifyInstallFailure(error))
            }
        }
    }

    /** Applies [change] only while installing, so a late event after cancel cannot resurrect the progress view. */
    private fun onInstalling(change: (SetupStage.Installing) -> SetupStage.Installing) =
        _stage.update { s -> (s as? SetupStage.Installing)?.let(change) ?: s }
}
