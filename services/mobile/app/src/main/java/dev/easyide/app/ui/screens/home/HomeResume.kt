package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.cropCorners
import dev.easyide.app.ui.kit.kitTag

/**
 * The last project as a two-row block: its name with the branch and changes inline and "Continue"
 * (the one filled action of the page) at the end, then, indented as a sub row, the file touched
 * last with its age. [selected] draws the crop corners: on a wide window the hero is the one
 * element that marks where the stage points.
 */
@Composable
internal fun ResumeSection(state: HomeUiState, nowMs: Long, selected: Boolean, flat: Boolean, onContinue: (ProjectListItem) -> Unit) {
    val item = state.resume ?: return
    val git = item.meta?.git
    val facts = joinParts(
        git?.branch,
        git?.takeIf { it.isDirty }?.let { stringResource(R.string.home_resume_changes, it.changedFiles) },
    )
    val file = state.resumeFile

    KitSection(stringResource(R.string.home_section_resume), flat = flat, collapsible = true) {
        Column(Modifier.kitTag("resume").cropCorners(enabled = selected)) {
            KitRow(
                title = item.project.name,
                subtitle = facts,
                leading = { ProjectGlyph(item.project.name) },
                actions = { KitButton(stringResource(R.string.home_resume_continue), { onContinue(item) }) },
            )
            if (file != null) {
                val age = relativeAge(nowMs, file.lastModifiedEpochMs).text()
                val (name, folder) = file.relativePath.fileNameAndFolder()
                KitRow(
                    title = name,
                    subtitle = folder,
                    mono = true,
                    // An empty icon slot puts the file name under the project name.
                    leading = {},
                    trailing = { MonoText(stringResource(R.string.home_resume_file, age)) },
                )
            }
        }
    }
}
