package dev.easyide.app.ui.screens.workspace

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.foundation.motionSpec
import dev.easyide.app.ui.theme.ControlSize
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.Stroke
import dev.easyide.app.ui.theme.editorColors
import dev.easyide.app.ui.theme.tabular

/** Left icon rail: switches what the side panel shows, and exits the project. */
@Composable
fun ActivityBar(
    explorerVisible: Boolean,
    sourceControlVisible: Boolean,
    terminalVisible: Boolean,
    onToggleExplorer: () -> Unit,
    onToggleSourceControl: () -> Unit,
    onToggleTerminal: () -> Unit,
    onShowCommands: () -> Unit,
    onShowExtensions: () -> Unit,
    onBack: () -> Unit,
    onCloseProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = editorColors

    Column(
        modifier = modifier
            .width(ControlSize.rail)
            .fillMaxHeight()
            .background(colors.activityBar),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        ActivityBarButton(Icons.AutoMirrored.Filled.ArrowBack, "Back to projects", false, onBack)
        ActivityBarButton(Icons.Filled.FolderCopy, "Explorer", explorerVisible, onToggleExplorer)
        ActivityBarButton(Icons.Filled.Difference, "Source control", sourceControlVisible, onToggleSourceControl)
        ActivityBarButton(Icons.Filled.Terminal, "Terminal", terminalVisible, onToggleTerminal)
        ActivityBarButton(
            Icons.Filled.Keyboard,
            stringResource(R.string.command_show_commands),
            false,
            onShowCommands,
        )
        ActivityBarButton(Icons.Filled.Extension, stringResource(R.string.command_show_extensions), false, onShowExtensions)
        Spacer(Modifier.weight(1f))
        ActivityBarButton(Icons.Filled.Close, stringResource(R.string.session_close_project), false, onCloseProject)
    }
}

@Composable
private fun ActivityBarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = editorColors
    val tint by animateColorAsState(
        targetValue = if (active) colors.activityIconActive else colors.activityIcon,
        animationSpec = motionSpec(),
        label = "activity-bar-tint",
    )

    Box(
        modifier = Modifier
            .size(ControlSize.rail)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Active indicator stripe in the accent: the rail's share of the
        // one-accent focus system (tab bar, tree pill, cursor).
        if (active) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(Stroke.accentBar)
                    .fillMaxHeight()
                    .background(colors.activityIndicator),
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(IconSize.l),
        )
    }
}

/** Bottom status bar: project, active file, dirty state, line count. */
@Composable
fun StatusBar(
    projectName: String,
    branch: String?,
    activeTab: EditorTab?,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    /** Contributed items: [leading] after the built-in left items, [trailing] before Save. */
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    /** Items of other features (language server status, diagnostic counts), right-aligned before [trailing]. */
    extra: @Composable RowScope.() -> Unit = {},
) {
    val colors = editorColors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.statusBar)
            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        // Tabular figures: the line count must not jitter as digits change.
        val textStyle = MaterialTheme.typography.labelSmall.tabular()
        Icon(
            imageVector = Icons.Filled.FolderCopy,
            contentDescription = null,
            tint = colors.statusBarText,
            modifier = Modifier.size(IconSize.xs),
        )
        Text(
            text = projectName,
            style = textStyle,
            color = colors.statusBarText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (activeTab != null) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = null,
                tint = colors.statusBarText,
                modifier = Modifier.size(IconSize.xs),
            )
            Text(
                text = activeTab.name + if (activeTab.isDirty) " *" else "",
                style = textStyle,
                color = colors.statusBarText,
            )
            Text(
                text = "${LineCount.of(activeTab.content)} lines",
                style = textStyle,
                color = colors.statusBarText,
            )
        }

        leading()

        Box(modifier = Modifier.weight(1f))
        extra()

        trailing()

        if (activeTab?.isDirty == true) {
            Row(
                modifier = Modifier.clickable(onClick = onSave),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(
                    imageVector = Icons.Filled.Save,
                    contentDescription = "Save",
                    tint = colors.statusBarText,
                    modifier = Modifier.size(IconSize.xs),
                )
                Text(
                    text = "Save",
                    style = textStyle,
                    color = colors.statusBarText,
                )
            }
        }
    }
}
