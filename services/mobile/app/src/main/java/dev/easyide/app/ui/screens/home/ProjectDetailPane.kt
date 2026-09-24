package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.ui.theme.EasyIdeFonts
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.components.SkeletonBar
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.app.ui.theme.sectionHeader
import dev.easyide.sandbox.files.RecentFile

/**
 * Everything about the selected project that can be shown without opening it,
 * with the two ways in: the workspace, or the workspace with its terminal up.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProjectDetail(
    item: ProjectListItem,
    recentFiles: List<RecentFile>?,
    nowMs: Long,
    onOpen: () -> Unit,
    onOpenTerminal: () -> Unit,
    actions: ProjectMenuActions,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = HomeMetrics.detailMaxWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                ProjectMonogram(item.project.name, HomeMetrics.monogramDetail)
                Column(modifier = Modifier.weight(1f)) {
                    if (showTitle) {
                        Text(
                            text = item.project.name,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = item.locationLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_detail_more))
                    ProjectContextMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, actions = actions)
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Button(onClick = onOpen) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.padding(end = Spacing.s))
                    Text(stringResource(R.string.home_detail_open))
                }
                OutlinedButton(onClick = onOpenTerminal) {
                    Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.padding(end = Spacing.s))
                    Text(stringResource(R.string.home_detail_open_terminal))
                }
            }

            DetailFacts(item, nowMs)
            RecentFilesSection(recentFiles, nowMs)
        }
    }
}

@Composable
private fun DetailFacts(item: ProjectListItem, nowMs: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        SectionHeader(R.string.home_detail_section_details)
        Fact(R.string.home_detail_environment) { EnvironmentChip(item) }
        Fact(R.string.home_detail_branch) {
            val git = item.meta?.git
            when {
                item.meta == null -> FactPlaceholder()
                git != null -> GitLabel(git)
                else -> FactText(stringResource(R.string.home_detail_no_git))
            }
        }
        Fact(R.string.home_detail_language) {
            val language = item.meta?.language
            when {
                item.meta == null -> FactPlaceholder()
                language != null -> LanguageLabel(language)
                else -> FactText(stringResource(R.string.home_detail_language_unknown))
            }
        }
        Fact(R.string.home_detail_last_opened) {
            FactText(relativeAge(nowMs, item.project.lastOpenedAtEpochMs).text())
        }
        Fact(R.string.home_detail_created) {
            FactText(relativeAge(nowMs, item.project.createdAtEpochMs).text())
        }
    }
}

@Composable
private fun RecentFilesSection(files: List<RecentFile>?, nowMs: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SectionHeader(R.string.home_detail_section_recent_files)
        when {
            files == null -> {
                val placeholder = MaterialTheme.colorScheme.surfaceContainerHigh
                repeat(HomeMetrics.RECENT_PLACEHOLDER_ROWS) {
                    SkeletonBar(placeholder, Spacing.l, Modifier.fillMaxWidth())
                }
            }
            files.isEmpty() -> FactText(stringResource(R.string.home_detail_no_recent_files))
            else -> files.forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = file.relativePath,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = EasyIdeFonts.mono),
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                    )
                    Text(
                        text = relativeAge(nowMs, file.lastModifiedEpochMs).text(),
                        style = MaterialTheme.typography.bodySmall,
                        color = editorColors.textMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: Int) {
    Text(
        text = stringResource(title).uppercase(),
        style = MaterialTheme.typography.sectionHeader,
        color = editorColors.textMuted,
    )
}

@Composable
private fun Fact(label: Int, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Text(
            text = stringResource(label),
            modifier = Modifier.weight(FACT_LABEL_WEIGHT),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.weight(FACT_VALUE_WEIGHT)) { content() }
    }
}

@Composable
private fun FactText(text: String) = Text(text = text, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun FactPlaceholder() = SkeletonBar(
    MaterialTheme.colorScheme.surfaceContainerHigh,
    Spacing.l,
    Modifier.fillMaxWidth(HomeMetrics.SKELETON_CHIP_FRACTION),
)

private const val FACT_LABEL_WEIGHT = 0.4f
private const val FACT_VALUE_WEIGHT = 0.6f
