package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.cropCorners
import dev.easyide.app.ui.kit.kitTag

/**
 * The last project, with what is worth knowing before going back in: branch and changes, and the
 * file touched last. "Continue" is the one filled action of the page. [selected] draws the crop
 * corners: on a wide window the hero is the one element that marks where the stage points.
 */
@Composable
internal fun ResumeSection(state: HomeUiState, nowMs: Long, selected: Boolean, onContinue: (ProjectListItem) -> Unit) {
    val item = state.resume ?: return
    val git = item.meta?.git
    val facts = joinParts(
        git?.branchLabel(),
        git?.takeIf { it.isDirty }?.let { stringResource(R.string.home_resume_changes, it.changedFiles) },
    )
    val file = state.resumeFile
    val space = Kit.space

    KitSection(stringResource(R.string.home_section_resume)) {
        Column(
            Modifier.kitTag("resume").cropCorners(enabled = selected).padding(space.l),
            verticalArrangement = Arrangement.spacedBy(space.xs),
        ) {
            BasicText(
                item.project.name,
                style = Kit.type.titleMedium.copy(color = Kit.colors.plainText),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (facts.isNotEmpty()) MonoText(facts, muted = false)
            if (file != null) {
                val age = relativeAge(nowMs, file.lastModifiedEpochMs).text()
                MonoText(joinParts(file.relativePath, stringResource(R.string.home_resume_file, age)), overflow = TextOverflow.StartEllipsis)
            }
            Row(Modifier.fillMaxWidth().padding(top = space.s), horizontalArrangement = Arrangement.End) {
                KitButton(stringResource(R.string.home_resume_continue), { onContinue(item) })
            }
        }
    }
}
