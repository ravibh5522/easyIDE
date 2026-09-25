package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.components.SkeletonBar
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.KitTwoColumnRow
import dev.easyide.app.ui.kit.Tone
import dev.easyide.sandbox.files.RecentFile

/** Environment, git, language and the two dates as label and value rows. Facts that need a read of the working tree show a bar until they arrive. */
@Composable
internal fun ProjectDetailsSection(item: ProjectListItem, nowMs: Long) {
    val meta = item.meta
    val env = item.environment
    val environmentLabel = stringResource(R.string.home_page_environment)
    KitSection(stringResource(R.string.home_page_section_details), collapsible = true) {
        KitTwoColumnRow(environmentLabel) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.s)) {
                if (env != null) {
                    MonoText(env.label, muted = false)
                    KitTag(env.state.tagText(), tone = env.state.tone())
                } else {
                    KitTag(stringResource(R.string.home_environment_missing), tone = Tone.Danger)
                }
            }
        }
        val git = meta?.git
        Fact(stringResource(R.string.home_page_branch), loading = meta == null) {
            if (git != null) {
                val changes = if (git.isDirty) stringResource(R.string.home_resume_changes, git.changedFiles) else null
                MonoText(joinParts(git.branch, changes), muted = false)
            } else {
                MonoText(stringResource(R.string.home_page_no_git))
            }
        }
        Fact(stringResource(R.string.home_page_language), loading = meta == null) {
            val language = meta?.language
            MonoText(if (language != null) stringResource(language.label) else stringResource(R.string.home_page_language_unknown), muted = language == null)
        }
        Fact(stringResource(R.string.home_page_last_opened)) { MonoText(relativeAge(nowMs, item.project.lastOpenedAtEpochMs).text()) }
        Fact(stringResource(R.string.home_page_created)) { MonoText(relativeAge(nowMs, item.project.createdAtEpochMs).text()) }
    }
}

@Composable
private fun Fact(label: String, loading: Boolean = false, value: @Composable () -> Unit) {
    KitTwoColumnRow(label) {
        if (loading) {
            SkeletonBar(Kit.colors.panelBorder, Kit.space.m, Modifier.width(HomeMetrics.skeletonValueWidth))
        } else {
            value()
        }
    }
}

/** The files changed last, newest first, one line each: the file name, its folder inline and its age at the end. */
@Composable
internal fun RecentFilesSection(files: List<RecentFile>?, nowMs: Long) {
    KitSection(stringResource(R.string.home_page_section_recent), count = files?.size?.takeIf { it > 0 }, collapsible = true) {
        when {
            files == null -> Column(Modifier.fillMaxWidth().padding(Kit.control.hPad), verticalArrangement = Arrangement.spacedBy(Kit.space.s)) {
                repeat(HomeMetrics.RECENT_PLACEHOLDER_ROWS) { SkeletonBar(Kit.colors.panelBorder, Kit.space.m, Modifier.fillMaxWidth()) }
            }
            files.isEmpty() -> KitEmptyState(EmptyArt.Prompt, stringResource(R.string.home_page_no_recent))
            else -> files.forEach { file ->
                val (name, folder) = file.relativePath.fileNameAndFolder()
                KitRow(
                    title = name,
                    subtitle = folder,
                    mono = true,
                    trailing = { MonoText(relativeAge(nowMs, file.lastModifiedEpochMs).text()) },
                )
            }
        }
    }
}
