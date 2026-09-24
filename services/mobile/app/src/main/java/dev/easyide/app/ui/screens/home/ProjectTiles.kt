package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.easyide.app.R
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.sandbox.git.GitSummary

/**
 * A project's tile: its initials over a tint that is a pure function of its name,
 * so it is recognisable at a glance and identical on every launch. The tint is a
 * categorical lane colour from the theme, kept translucent so the letters, in the
 * ordinary text colour, always have contrast.
 */
@Composable
internal fun ProjectMonogram(name: String, size: Dp, modifier: Modifier = Modifier) {
    val lanes = editorColors.lanes
    val tint = lanes[monogramBucket(name, lanes.size)]
    Box(
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .background(tint.copy(alpha = HomeMetrics.MONOGRAM_TINT_ALPHA))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = monogramLetters(name),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A coloured dot and the language's name. The dot is decoration; the text carries the meaning. */
@Composable
internal fun LanguageLabel(language: ProjectLanguage, modifier: Modifier = Modifier) {
    val lanes = editorColors.lanes
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            Modifier
                .size(HomeMetrics.languageDot)
                .background(lanes[language.tint % lanes.size], CircleShape)
                .clearAndSetSemantics { },
        )
        Text(
            text = stringResource(language.label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Branch name and, when the working tree differs from HEAD, how many paths do. */
@Composable
internal fun GitLabel(git: GitSummary, modifier: Modifier = Modifier) {
    val description = if (git.isDirty) {
        stringResource(R.string.home_git_dirty_description, git.branch, git.changedFiles)
    } else {
        stringResource(R.string.home_git_clean_description, git.branch)
    }
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Icon(
            imageVector = Icons.Filled.AccountTree,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.s),
        )
        Text(
            text = git.branch,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (git.isDirty) {
            Box(
                Modifier
                    .size(HomeMetrics.languageDot)
                    .background(editorColors.git.modified, CircleShape),
            )
            Text(
                text = git.changedFiles.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = editorColors.git.modified,
            )
        }
    }
}
