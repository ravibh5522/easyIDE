package dev.easyide.app.ui.screens.extensions

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.extensions.authoring.ScaffoldRefusal
import dev.easyide.app.extensions.authoring.ScaffoldResult
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitBanner
import dev.easyide.app.ui.kit.KitChoice
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.Tone
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

/** Whether publisher and name make an extension id; the ids are lower-case, so input is folded first. */
internal fun validExtensionId(publisher: String, name: String): Boolean =
    ExtensionId.of(publisher.trim().lowercase(), name.trim().lowercase()) != null

/**
 * The in-app "Create extension" (M5): pick a declarative template, publisher, name and the
 * project to write `<name>/` into; then "Install from this folder". Same templates and
 * substitution as `easyide-ext init`.
 */
@Composable
internal fun CreateExtensionDialogs(state: CreateState, projects: List<ProjectRecord>, developerMode: Boolean, viewModel: ExtensionsViewModel) {
    when (state) {
        CreateState.Idle -> Unit
        CreateState.Editing -> CreateForm(projects, viewModel)
        is CreateState.Refused -> ProblemDialog(stringResource(R.string.create_ext_refused_title), listOf(refusalText(state)), viewModel::closeCreate)
        is CreateState.Created -> KitDialog(
            title = stringResource(R.string.create_ext_created_title, state.result.id),
            onDismiss = viewModel::closeCreate,
            confirm = KitAction(stringResource(R.string.create_ext_install)) { viewModel.installCreated(state.result) },
            dismiss = KitAction(stringResource(R.string.ext_prompt_close), viewModel::closeCreate),
        ) {
            DialogText(stringResource(R.string.create_ext_created_body, state.result.relativePath))
            DialogText(
                if (developerMode) stringResource(R.string.create_ext_dev_hint, state.result.relativePath) else stringResource(R.string.create_ext_dev_off_hint),
                muted = true,
            )
        }
    }
}

@Composable
private fun CreateForm(projects: List<ProjectRecord>, viewModel: ExtensionsViewModel) {
    var template by rememberSaveable { mutableStateOf(ExtensionTemplates.DECLARATIVE.first()) }
    var publisher by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    val target = projects.firstOrNull { it.id == projectId } ?: projects.firstOrNull()
    val valid = validExtensionId(publisher, name)
    val idError = if ((publisher.isNotBlank() || name.isNotBlank()) && !valid) stringResource(R.string.create_ext_id_hint) else null
    val gap = Modifier.padding(top = Kit.space.s)
    val templateLabels = ExtensionTemplates.DECLARATIVE.associateWith { templateLabel(it) }
    KitDialog(
        title = stringResource(R.string.create_ext_title),
        onDismiss = viewModel::closeCreate,
        confirm = target?.takeIf { valid }?.let { p ->
            KitAction(stringResource(R.string.create_ext_create)) { viewModel.createExtension(template, publisher, name, displayName, p.id) }
        },
        dismiss = KitAction(stringResource(R.string.ext_prompt_cancel), viewModel::closeCreate),
    ) {
        DialogHeading(stringResource(R.string.create_ext_template))
        KitChoice(ExtensionTemplates.DECLARATIVE, template, { templateLabels.getValue(it) }, { template = it })
        DialogText(stringResource(R.string.create_ext_wasm_note), muted = true)
        KitField(publisher, { publisher = it }, gap, label = stringResource(R.string.create_ext_publisher), mono = true, error = idError)
        KitField(name, { name = it }, gap, label = stringResource(R.string.create_ext_name), mono = true)
        KitField(displayName, { displayName = it }, gap, label = stringResource(R.string.create_ext_display_name))
        DialogHeading(stringResource(R.string.create_ext_project))
        if (target == null) KitBanner(stringResource(R.string.create_ext_no_project), tone = Tone.Warning)
        else KitChoice(projects, target, { it.name }, { projectId = it.id })
    }
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
