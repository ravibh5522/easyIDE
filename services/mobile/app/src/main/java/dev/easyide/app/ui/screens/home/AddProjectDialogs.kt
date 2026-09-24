package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import dev.easyide.app.R
import dev.easyide.sandbox.ProjectNames
import dev.easyide.sandbox.model.SandboxEnvironment
import dev.easyide.app.ui.theme.Spacing

/**
 * Clone a repository into a new project. Cloning runs the guest's own `git`, so
 * only an installed Linux environment can do it: the picker lists just those,
 * and the dialog says so when there are none instead of failing later.
 */
@Composable
internal fun CloneDialog(
    environments: List<SandboxEnvironment>,
    suggestedEnvironmentId: String?,
    otherNames: Collection<String>,
    busy: Boolean,
    failure: HomeFailure?,
    onConfirm: (CloneUrl, name: String, environmentId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var address by rememberSaveable { mutableStateOf("") }
    var typedName by rememberSaveable { mutableStateOf<String?>(null) }
    val parsed = CloneUrl.parse(address)
    val url = (parsed as? CloneUrl.Result.Valid)?.value
    // The repository's name until the user types their own; typing a name here is a deliberate override.
    val name = typedName ?: url?.suggestedName.orEmpty()
    var environmentId by rememberSaveable { mutableStateOf(suggestedEnvironmentId) }
    val selected = environmentId?.takeIf { id -> environments.any { it.id == id } } ?: environments.firstOrNull()?.id
    val problem = ProjectNames.problem(name, otherNames)
    val canConfirm = !busy && url != null && problem == null && selected != null

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.home_clone_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.home_clone_url_label)) },
                    placeholder = { Text(stringResource(R.string.home_clone_url_placeholder)) },
                    singleLine = true,
                    enabled = !busy,
                    isError = parsed is CloneUrl.Result.Invalid && address.isNotBlank(),
                    supportingText = {
                        val invalid = parsed as? CloneUrl.Result.Invalid
                        if (invalid != null && address.isNotBlank()) Text(stringResource(invalid.problem.message()))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { typedName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.new_project_name_label)) },
                    singleLine = true,
                    enabled = !busy,
                    isError = problem == ProjectNames.Problem.DUPLICATE,
                    supportingText = {
                        if (problem == ProjectNames.Problem.DUPLICATE) Text(stringResource(R.string.home_error_name_taken))
                    },
                )
                if (environments.isEmpty()) {
                    Text(stringResource(R.string.home_error_no_ready_environment), color = MaterialTheme.colorScheme.error)
                } else {
                    Text(stringResource(R.string.home_clone_environment), style = MaterialTheme.typography.labelLarge)
                    EnvironmentPicker(environments, selected, enabled = !busy) { environmentId = it }
                }
                failure?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
                if (busy) {
                    Text(stringResource(R.string.home_clone_progress), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (url != null && selected != null) onConfirm(url, name, selected) },
                enabled = canConfirm,
            ) { Text(stringResource(R.string.home_clone_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun CloneUrl.Problem.message(): Int = when (this) {
    CloneUrl.Problem.EMPTY -> R.string.home_clone_url_empty
    CloneUrl.Problem.UNSUPPORTED_SCHEME -> R.string.home_clone_url_unsupported
    CloneUrl.Problem.MALFORMED -> R.string.home_clone_url_malformed
}

/**
 * Import a folder the user picked: its files become the project's starting
 * content and stay mirrored to that folder. The project needs an environment to
 * run in, any of the existing ones will do.
 */
@Composable
internal fun ImportDialog(
    suggestedName: String,
    environments: List<SandboxEnvironment>,
    suggestedEnvironmentId: String?,
    otherNames: Collection<String>,
    busy: Boolean,
    failure: HomeFailure?,
    onConfirm: (name: String, environmentId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(suggestedName) { mutableStateOf(suggestedName) }
    var environmentId by rememberSaveable { mutableStateOf(suggestedEnvironmentId) }
    val selected = environmentId?.takeIf { id -> environments.any { it.id == id } } ?: environments.firstOrNull()?.id
    val problem = ProjectNames.problem(name, otherNames)
    val canConfirm = !busy && problem == null && selected != null

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.home_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text(stringResource(R.string.home_import_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.new_project_name_label)) },
                    singleLine = true,
                    enabled = !busy,
                    isError = problem == ProjectNames.Problem.DUPLICATE,
                    supportingText = {
                        if (problem == ProjectNames.Problem.DUPLICATE) Text(stringResource(R.string.home_error_name_taken))
                    },
                )
                if (environments.isEmpty()) {
                    Text(stringResource(R.string.home_import_no_environment), color = MaterialTheme.colorScheme.error)
                } else {
                    EnvironmentPicker(environments, selected, enabled = !busy) { environmentId = it }
                }
                failure?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
                if (busy) {
                    Text(stringResource(R.string.home_import_progress), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let { onConfirm(name, it) } }, enabled = canConfirm) {
                Text(stringResource(R.string.home_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
