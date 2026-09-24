package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.extensions.authoring.ScaffoldRefusal
import dev.easyide.app.extensions.authoring.ScaffoldResult
import dev.easyide.extensions.authoring.ExtensionTemplates
import dev.easyide.extensions.manifest.ExtensionId
import dev.easyide.sandbox.model.ProjectRecord

/** "Create extension" from the form to what was written. */
sealed interface CreateState {
    data object Idle : CreateState
    data object Editing : CreateState
    data class Created(val result: ScaffoldResult.Created) : CreateState
    data class Refused(val reason: ScaffoldRefusal, val detail: String) : CreateState
}

/**
 * The in-app "Create extension" (M5): pick a declarative template, publisher, name and the
 * project to write `<name>/` into; then "Install from this folder". Same templates and
 * substitution as `easyide-ext init`.
 */
@Composable
fun CreateExtensionDialogs(state: CreateState, projects: List<ProjectRecord>, developerMode: Boolean, viewModel: ExtensionsViewModel) {
    when (state) {
        CreateState.Idle -> Unit
        CreateState.Editing -> CreateForm(projects, viewModel)
        is CreateState.Refused -> ProblemDialog(stringResource(R.string.create_ext_refused_title), listOf(refusalText(state)), viewModel::closeCreate)
        is CreateState.Created -> AlertDialog(
            onDismissRequest = viewModel::closeCreate,
            title = { Text(stringResource(R.string.create_ext_created_title, state.result.id)) },
            text = {
                Column {
                    Text(stringResource(R.string.create_ext_created_body, state.result.relativePath))
                    Text(
                        if (developerMode) stringResource(R.string.create_ext_dev_hint, state.result.relativePath) else stringResource(R.string.create_ext_dev_off_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.installCreated(state.result) }) { Text(stringResource(R.string.create_ext_install)) } },
            dismissButton = { TextButton(onClick = viewModel::closeCreate) { Text(stringResource(R.string.ext_prompt_close)) } },
        )
    }
}

@Composable
private fun CreateForm(projects: List<ProjectRecord>, viewModel: ExtensionsViewModel) {
    var template by rememberSaveable { mutableStateOf(ExtensionTemplates.DECLARATIVE.first()) }
    var publisher by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    val target = projectId ?: projects.firstOrNull()?.id
    val valid = ExtensionId.of(publisher.trim().lowercase(), name.trim().lowercase()) != null
    AlertDialog(
        onDismissRequest = viewModel::closeCreate,
        title = { Text(stringResource(R.string.create_ext_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = SHEET_MAX_DP.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.create_ext_template), style = MaterialTheme.typography.titleSmall)
                FlowRow {
                    ExtensionTemplates.DECLARATIVE.forEach { t ->
                        FilterChip(selected = t == template, onClick = { template = t }, label = { Text(templateLabel(t)) })
                    }
                }
                Text(stringResource(R.string.create_ext_wasm_note), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(publisher, { publisher = it }, label = { Text(stringResource(R.string.create_ext_publisher)) }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.create_ext_name)) }, singleLine = true)
                OutlinedTextField(displayName, { displayName = it }, label = { Text(stringResource(R.string.create_ext_display_name)) }, singleLine = true)
                Text(stringResource(R.string.create_ext_id_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.create_ext_project), style = MaterialTheme.typography.titleSmall)
                if (projects.isEmpty()) Text(stringResource(R.string.create_ext_no_project), color = MaterialTheme.colorScheme.error)
                FlowRow {
                    projects.forEach { p -> FilterChip(selected = p.id == target, onClick = { projectId = p.id }, label = { Text(p.name) }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { target?.let { viewModel.createExtension(template, publisher, name, displayName, it) } }, enabled = valid && target != null) {
                Text(stringResource(R.string.create_ext_create))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::closeCreate) { Text(stringResource(R.string.ext_prompt_cancel)) } },
    )
}

@Composable
private fun templateLabel(t: String): String = when (t) {
    "theme" -> stringResource(R.string.create_ext_template_theme)
    "snippets" -> stringResource(R.string.create_ext_template_snippets)
    "language-pack" -> stringResource(R.string.create_ext_template_language_pack)
    "lsp-pack" -> stringResource(R.string.create_ext_template_lsp_pack)
    "toolbar-command" -> stringResource(R.string.create_ext_template_toolbar_command)
    else -> t
}

@Composable
private fun refusalText(r: CreateState.Refused): String = when (r.reason) {
    ScaffoldRefusal.EXISTS -> stringResource(R.string.create_ext_refused_exists, r.detail)
    ScaffoldRefusal.INVALID_ID -> stringResource(R.string.create_ext_refused_id, r.detail)
    ScaffoldRefusal.UNKNOWN_TEMPLATE, ScaffoldRefusal.DAMAGED_TEMPLATE -> stringResource(R.string.create_ext_refused_template, r.detail)
    ScaffoldRefusal.IO -> stringResource(R.string.create_ext_refused_io, r.detail)
}
