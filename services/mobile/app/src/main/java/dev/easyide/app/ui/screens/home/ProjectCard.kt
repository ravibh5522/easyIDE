package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.components.EnvironmentBadge
import dev.easyide.app.ui.components.SkeletonBar
import dev.easyide.app.ui.components.clickableWithContextMenu
import dev.easyide.app.ui.components.label
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.Stroke

/**
 * One project in the list: tile, name, where it lives and when it was last opened,
 * then environment, git and language. The facts that need a read of the working
 * tree ([ProjectListItem.meta]) show placeholders until they arrive, so the card does
 * not change height when they do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProjectCard(
    item: ProjectListItem,
    nowMs: Long,
    selected: Boolean,
    onClick: () -> Unit,
    actions: ProjectMenuActions,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { role = Role.Button; this.selected = selected }
                .clickableWithContextMenu(
                    onClick = onClick,
                    onContextMenu = { menuOpen = true },
                    onLongClickLabel = stringResource(R.string.home_card_long_click_label),
                ),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(if (selected) Stroke.accentBar else Stroke.hairline, border),
        ) {
            Row(
                modifier = Modifier.padding(Spacing.m),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                ProjectMonogram(item.project.name, HomeMetrics.monogramList)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        text = item.project.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.home_card_subtitle,
                            item.locationLabel(),
                            relativeAge(nowMs, item.project.lastOpenedAtEpochMs).text(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        EnvironmentChip(item)
                        item.meta?.git?.let { GitLabel(it) }
                        item.meta?.language?.let { LanguageLabel(it) }
                    }
                }
            }
        }
        ProjectContextMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, actions = actions)
    }
}

/** Where the project's files live, for a card or the detail header. */
@Composable
internal fun ProjectListItem.locationLabel(): String =
    meta?.externalFolderName?.let { stringResource(R.string.home_location_folder, it) }
        ?: if (project.externalFolderUri != null) {
            stringResource(R.string.home_location_linked_folder)
        } else {
            stringResource(R.string.home_location_app_storage)
        }

@Composable
internal fun EnvironmentChip(item: ProjectListItem) {
    val environment = item.environment
    if (environment != null) {
        EnvironmentBadge(
            label = environment.label,
            state = environment.state,
            sharedWithCount = item.sharedWithCount,
        )
    } else {
        Text(
            text = stringResource(R.string.home_environment_missing),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** The shape of a [ProjectCard], for the first frame before the store has answered. */
@Composable
internal fun SkeletonProjectCard(modifier: Modifier = Modifier) {
    val placeholder = MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            SkeletonBar(placeholder, HomeMetrics.monogramList, Modifier.width(HomeMetrics.monogramList))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                SkeletonBar(placeholder, Spacing.l, Modifier.fillMaxWidth(HomeMetrics.SKELETON_TITLE_FRACTION))
                SkeletonBar(placeholder, Spacing.m, Modifier.fillMaxWidth(HomeMetrics.SKELETON_SUBTITLE_FRACTION))
                SkeletonBar(placeholder, Spacing.l, Modifier.fillMaxWidth(HomeMetrics.SKELETON_CHIP_FRACTION))
            }
        }
    }
}
