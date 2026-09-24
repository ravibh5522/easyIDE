package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import dev.easyide.app.R
import dev.easyide.app.ui.components.ChoiceCard
import dev.easyide.app.ui.components.EnvironmentBadge
import dev.easyide.app.ui.components.label
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.sandbox.ProjectNames
import dev.easyide.sandbox.model.SandboxEnvironment

/**
 * A dialog with one name field, used by rename and duplicate. The name is
 * checked against the other projects as it is typed - the same rule
 * [dev.easyide.sandbox.ProjectManager] enforces - so the confirm button is only
 * ever enabled for a name that will be accepted.
 *
 * @param initial pre-filled and fully selected, so typing replaces it.
 * @param allowUnchanged whether confirming the initial name is meaningful (a
 *   duplicate's proposed name is; a rename to the same name is not).
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
    var value by rememberSaveable(initial, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val problem = ProjectNames.problem(value.text, otherNames)
    val canConfirm = !busy && problem == null && (allowUnchanged || value.text.trim() != initial)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    label = { Text(stringResource(R.string.new_project_name_label)) },
                    singleLine = true,
                    enabled = !busy,
                    isError = problem == ProjectNames.Problem.DUPLICATE || failure != null,
                    supportingText = {
                        when {
                            problem == ProjectNames.Problem.DUPLICATE -> Text(stringResource(R.string.home_error_name_taken))
                            failure != null -> Text(failure.text())
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm(value.text) }),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = canConfirm) { Text(stringResource(confirmLabel)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Deleting is the one destructive action here, so it states exactly what goes
 * (the project's files in app storage) and what stays (its environment, and any
 * folder it was linked to) before asking.
 */
@Composable
internal fun DeleteProjectDialog(
    item: ProjectListItem,
    busy: Boolean,
    failure: HomeFailure?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.home_delete_title, item.project.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text(stringResource(R.string.home_delete_body))
                Text(
                    text = if (item.project.externalFolderUri != null) {
                        stringResource(R.string.home_delete_keeps_linked)
                    } else {
                        stringResource(R.string.home_delete_keeps_environment)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                failure?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.home_delete_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
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

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.home_change_environment_title, item.project.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text(stringResource(R.string.home_change_environment_body))
                EnvironmentPicker(environments, selected, enabled = !busy) { selected = it }
                failure?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected) },
                enabled = !busy && selected != item.project.environmentId,
            ) { Text(stringResource(R.string.home_change_environment_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** The environments to choose from, as radio cards; the state badge says which are installed. */
@Composable
internal fun EnvironmentPicker(
    environments: List<SandboxEnvironment>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier.selectableGroup().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        environments.forEach { environment ->
            ChoiceCard(
                selected = environment.id == selectedId,
                title = environment.label,
                body = environment.backend.label(),
                onClick = { if (enabled) onSelect(environment.id) },
                trailing = { EnvironmentBadge(environment.state.label(), environment.state, sharedWithCount = 0) },
            )
        }
    }
}
