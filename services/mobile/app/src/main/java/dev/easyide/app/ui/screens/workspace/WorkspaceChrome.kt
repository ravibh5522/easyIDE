package dev.easyide.app.ui.screens.workspace

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.foundation.motionSpec
import dev.easyide.app.ui.theme.editorColors

/** Left icon rail: switches what the side panel shows, and exits the project. */
@Composable
fun ActivityBar(
    explorerVisible: Boolean,
    sourceControlVisible: Boolean,
    terminalVisible: Boolean,
    onToggleExplorer: () -> Unit,
    onToggleSourceControl: () -> Unit,
    onToggleTerminal: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = editorColors

    Column(
        modifier = modifier
            .width(ACTIVITY_BAR_WIDTH_DP.dp)
            .fillMaxHeight()
            .background(colors.activityBar),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ActivityBarButton(Icons.AutoMirrored.Filled.ArrowBack, "Back to projects", false, onBack)
        ActivityBarButton(Icons.Filled.FolderCopy, "Explorer", explorerVisible, onToggleExplorer)
        ActivityBarButton(Icons.Filled.Difference, "Source control", sourceControlVisible, onToggleSourceControl)
        ActivityBarButton(Icons.Filled.Terminal, "Terminal", terminalVisible, onToggleTerminal)
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
        targetValue = if (active) Color.White else colors.gutterText,
        animationSpec = motionSpec(),
        label = "activity-bar-tint",
    )

    Box(
        modifier = Modifier
            .size(ACTIVITY_BAR_WIDTH_DP.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Active indicator stripe, same language as VS Code's rail.
        if (active) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(INDICATOR_WIDTH_DP.dp)
                    .fillMaxHeight()
                    .background(Color.White),
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(ACTIVITY_ICON_DP.dp),
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
) {
    val colors = editorColors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.statusBar)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.FolderCopy,
            contentDescription = null,
            tint = colors.statusBarText,
            modifier = Modifier.size(STATUS_ICON_DP.dp),
        )
        Text(
            text = projectName,
            style = MaterialTheme.typography.labelSmall,
            color = colors.statusBarText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (activeTab != null) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = null,
                tint = colors.statusBarText,
                modifier = Modifier.size(STATUS_ICON_DP.dp),
            )
            Text(
                text = activeTab.name + if (activeTab.isDirty) " *" else "",
                style = MaterialTheme.typography.labelSmall,
                color = colors.statusBarText,
            )
            Text(
                text = "${activeTab.content.count { it == '\n' } + 1} lines",
                style = MaterialTheme.typography.labelSmall,
                color = colors.statusBarText,
            )
        }

        Box(modifier = Modifier.weight(1f))

        if (activeTab?.isDirty == true) {
            Row(
                modifier = Modifier.clickable(onClick = onSave),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Save,
                    contentDescription = "Save",
                    tint = colors.statusBarText,
                    modifier = Modifier.size(STATUS_ICON_DP.dp),
                )
                Text(
                    text = "Save",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.statusBarText,
                )
            }
        }
    }
}

private const val ACTIVITY_BAR_WIDTH_DP = 48
private const val ACTIVITY_ICON_DP = 22
private const val INDICATOR_WIDTH_DP = 2
private const val STATUS_ICON_DP = 14
