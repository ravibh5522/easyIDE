package dev.easyide.app.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitProgress
import dev.easyide.sandbox.ProjectNames
import dev.easyide.sandbox.model.SandboxEnvironment

/** The environment to use: the remembered one if it still exists, else the first that does. */
private fun chosenEnvironment(remembered: String?, environments: List<SandboxEnvironment>): String? =
    remembered?.takeIf { id -> environments.any { it.id == id } } ?: environments.firstOrNull()?.id

/**
 * Clone a repository into a new project. Cloning runs the guest's own `git`, so only an
 * installed environment can do it: [environments] lists just those, and the host offers to
 * install Linux instead of opening this dialog when there are none.
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
    var environmentId by rememberSaveable { mutableStateOf(suggestedEnvironmentId) }
    val parsed = CloneUrl.parse(address)
    val url = (parsed as? CloneUrl.Result.Valid)?.value
    // The repository's name until the user types their own; typing a name here is a deliberate override.
    val name = typedName ?: url?.suggestedName.orEmpty()
    val selected = chosenEnvironment(environmentId, environments)
    val problem = ProjectNames.problem(name, otherNames)
    val invalid = (parsed as? CloneUrl.Result.Invalid)?.takeIf { address.isNotBlank() }
    val ready = url != null && problem == null && selected != null

    KitDialog(
        title = stringResource(R.string.home_clone_title),
        onDismiss = { if (!busy) onDismiss() },
        confirm = if (!busy && ready) {
            KitAction(stringResource(R.string.home_clone_confirm)) { onConfirm(url!!, name, selected!!) }
        } else {
            null
        },
        dismiss = cancelAction(busy, onDismiss),
    ) {
        DialogBody {
            KitField(
                value = address,
                onValueChange = { address = it },
                label = stringResource(R.string.home_clone_url_label),
                hint = stringResource(R.string.home_clone_url_placeholder),
                error = invalid?.let { stringResource(it.problem.message()) },
                mono = true,
                enabled = !busy,
                keyboard = URL_KEYBOARD,
            )
            KitField(
                value = name,
                onValueChange = { typedName = it },
                label = stringResource(R.string.new_project_name_label),
                error = if (problem == ProjectNames.Problem.DUPLICATE) stringResource(R.string.home_error_name_taken) else null,
                enabled = !busy,
                keyboard = NAME_KEYBOARD,
            )
            BodyText(stringResource(R.string.home_clone_environment), muted = true)
            EnvironmentChoice(environments, selected, enabled = !busy) { environmentId = it }
            FailureBanner(failure)
            if (busy) KitProgress(null)
        }
    }
}

private fun CloneUrl.Problem.message(): Int = when (this) {
    CloneUrl.Problem.EMPTY -> R.string.home_clone_url_empty
    CloneUrl.Problem.UNSUPPORTED_SCHEME -> R.string.home_clone_url_unsupported
    CloneUrl.Problem.MALFORMED -> R.string.home_clone_url_malformed
}

/**
 * Import a folder the user picked: its files become the project's starting content and stay
 * mirrored to that folder. The project runs in one of the existing environments.
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
    val selected = chosenEnvironment(environmentId, environments)
    val problem = ProjectNames.problem(name, otherNames)

    KitDialog(
        title = stringResource(R.string.home_import_title),
        onDismiss = { if (!busy) onDismiss() },
        confirm = if (!busy && problem == null && selected != null) {
            KitAction(stringResource(R.string.home_import_confirm)) { onConfirm(name, selected) }
        } else {
            null
        },
        dismiss = cancelAction(busy, onDismiss),
    ) {
        DialogBody {
            BodyText(stringResource(R.string.home_import_body), muted = true)
            KitField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.new_project_name_label),
                error = if (problem == ProjectNames.Problem.DUPLICATE) stringResource(R.string.home_error_name_taken) else null,
                enabled = !busy,
                keyboard = NAME_KEYBOARD,
            )
            BodyText(stringResource(R.string.home_import_environment), muted = true)
            EnvironmentChoice(environments, selected, enabled = !busy) { environmentId = it }
            FailureBanner(failure)
            if (busy) KitProgress(null)
        }
    }
}
