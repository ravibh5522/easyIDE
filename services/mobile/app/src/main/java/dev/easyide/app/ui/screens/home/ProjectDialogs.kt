package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitProgress
import dev.easyide.app.ui.kit.Tone
import dev.easyide.sandbox.ProjectNames
import dev.easyide.sandbox.model.SandboxEnvironment

/** The dismiss action every dialog has; it does nothing while an operation runs, which cannot be cancelled. */
@Composable
internal fun cancelAction(busy: Boolean, onDismiss: () -> Unit) = KitAction(stringResource(R.string.action_cancel)) { if (!busy) onDismiss() }

/**
 * A dialog with one name field, used by rename and duplicate. The name is checked against the
 * other projects as it is typed (the rule [dev.easyide.sandbox.ProjectManager] enforces), and the
 * confirm action only appears for a name that will be accepted.
 *
 * @param allowUnchanged whether confirming the initial name is meaningful (a duplicate's
 *   proposed name is; a rename to the same name is not).
 */
@Composable
internal fun NameDialog(
    title: Int,
    confirmLabel: Int,
    initial: String,
    otherNames: Collection<String>,
    allowUnchanged: Boolean,
    busy: Boolean,
    failure: HomeFailure?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    val problem = ProjectNames.problem(value, otherNames)
    val canConfirm = !busy && problem == null && (allowUnchanged || value.trim() != initial)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val error = when {
        problem == ProjectNames.Problem.DUPLICATE -> stringResource(R.string.home_error_name_taken)
        failure != null -> failure.text()
        else -> null
    }

    KitDialog(
        title = stringResource(title),
        onDismiss = { if (!busy) onDismiss() },
        confirm = if (canConfirm) KitAction(stringResource(confirmLabel)) { onConfirm(value) } else null,
        dismiss = cancelAction(busy, onDismiss),
    ) {
        DialogBody {
            KitField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.focusRequester(focus),
                label = stringResource(R.string.new_project_name_label),
                error = error,
                enabled = !busy,
                keyboard = NAME_KEYBOARD,
                keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm(value) }),
            )
            if (busy) KitProgress(null)
        }
    }
}

/**
 * Deleting is the one irreversible action here, so it names the project, says what goes (its
 * files in app storage) and what stays (its environment, any linked folder), and asks for the
 * name to be typed (U-CMP-04). Confirm appears only once the name matches.
 */
@Composable
internal fun DeleteProjectDialog(
    item: ProjectListItem,
    busy: Boolean,
    failure: HomeFailure?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by rememberSaveable(item.project.id) { mutableStateOf("") }
    val keeps = if (item.project.externalFolderUri != null) R.string.home_delete_keeps_linked else R.string.home_delete_keeps_environment

    KitDialog(
        title = stringResource(R.string.home_delete_title, item.project.name),
        onDismiss = { if (!busy) onDismiss() },
        tone = Tone.Danger,
        confirm = if (!busy && deleteConfirmed(typed, item.project.name)) KitAction(stringResource(R.string.home_delete_confirm), onConfirm) else null,
        dismiss = cancelAction(busy, onDismiss),
    ) {
        DialogBody {
            BodyText(stringResource(R.string.home_delete_body))
            BodyText(stringResource(keeps), muted = true)
            KitField(
                value = typed,
                onValueChange = { typed = it },
                label = stringResource(R.string.home_delete_type_name),
                enabled = !busy,
                keyboard = NAME_KEYBOARD,
            )
            FailureBanner(failure)
            if (busy) KitProgress(null)
        }
    }
}

@Composable
internal fun ChangeEnvironmentDialog(
    item: ProjectListItem,
    environments: List<SandboxEnvironment>,
    busy: Boolean,
    failure: HomeFailure?,
    onConfirm: (environmentId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable(item.project.id) { mutableStateOf(item.project.environmentId) }

    KitDialog(
        title = stringResource(R.string.home_change_environment_title, item.project.name),
        onDismiss = { if (!busy) onDismiss() },
        confirm = if (!busy && selected != item.project.environmentId) {
            KitAction(stringResource(R.string.home_change_environment_confirm)) { onConfirm(selected) }
        } else {
            null
        },
        dismiss = cancelAction(busy, onDismiss),
    ) {
        DialogBody {
            BodyText(stringResource(R.string.home_change_environment_body), muted = true)
            EnvironmentChoice(environments, selected, enabled = !busy) { selected = it }
            FailureBanner(failure)
            if (busy) KitProgress(null)
        }
    }
}
