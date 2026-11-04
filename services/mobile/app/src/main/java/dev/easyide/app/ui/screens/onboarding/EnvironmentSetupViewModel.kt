package dev.easyide.app.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.easyide.app.data.SandboxImages
import dev.easyide.sandbox.bootstrap.InstallEvent
import dev.easyide.sandbox.model.SandboxImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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
 * The preset picker in front of [BackgroundInstaller]: the install itself outlives this ViewModel, so
 * this only remembers which preset is selected and mirrors the installer's stage.
 */
class EnvironmentSetupViewModel(
    private val installer: BackgroundInstaller,
    private val images: List<SandboxImage> = SandboxImages.CATALOG,
) : ViewModel() {

    private val selectedImageId = MutableStateFlow(images.first().id)

    val state: StateFlow<EnvironmentSetupState> = combine(selectedImageId, installer.stage) { id, stage ->
        EnvironmentSetupState(images, id, stage)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        EnvironmentSetupState(images, images.first().id, installer.stage.value),
    )

    fun onImageSelected(imageId: String) {
        selectedImageId.value = imageId
    }

    fun install() = installer.start(images.first { it.id == selectedImageId.value })

    fun cancel() = installer.cancel()

    fun backToChoosing() = installer.reset()
}
