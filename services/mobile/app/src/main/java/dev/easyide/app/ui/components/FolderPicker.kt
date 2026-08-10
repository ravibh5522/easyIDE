package dev.easyide.app.ui.components

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.easyide.sandbox.external.ExternalFolderSync

/**
 * Launches the system folder picker (Storage Access Framework) and takes a
 * persistable read+write grant on whatever the user picks, so the choice
 * survives past this one activity result and past app restarts.
 *
 * There is no manifest permission and no runtime permission dialog here - the
 * picker itself *is* the user's consent. What can still go wrong:
 *  - the user backs out of the picker: the system callback fires with a null
 *    [Uri] and [onPicked]/[onFailed] are both skipped - not an error, just
 *    nothing changed;
 *  - [ExternalFolderSync.takePersistableAccess] throws: some providers do not
 *    support persistable grants, so [onFailed] runs instead of silently
 *    losing write access the next time the app restarts.
 *
 * @return a function that opens the picker, pre-aimed at internal storage.
 */
@Composable
fun rememberFolderPicker(
    externalFolderSync: ExternalFolderSync,
    onPicked: (Uri) -> Unit,
    onFailed: () -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        externalFolderSync.takePersistableAccess(uri)
            .onSuccess { onPicked(uri) }
            .onFailure { onFailed() }
    }
    return remember(launcher) { { launcher.launch(internalStorageHint()) } }
}

/**
 * Points the picker at "Internal storage" instead of the provider-chooser
 * screen. Advisory only: if this authority is absent on a given OEM picker,
 * the system just falls back to its own default starting screen - there is no
 * failure mode to handle.
 */
private fun internalStorageHint(): Uri =
    DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, PRIMARY_VOLUME_ROOT)

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private const val PRIMARY_VOLUME_ROOT = "primary:"
