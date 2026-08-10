package dev.easyide.app.ui.screens.workspace

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.easyide.app.ui.foundation.motionSpec
import dev.easyide.app.ui.theme.editorColors

/**
 * Editor tab strip. A dirty buffer shows a dot in place of the close button
 * until hovered/tapped - the same affordance VS Code uses, and the only signal
 * that unsaved work exists.
 */
@Composable
fun EditorTabBar(
    tabs: List<EditorTab>,
    activeTabPath: String?,
    onTabSelected: (String) -> Unit,
    onTabClosed: (String) -> Unit,
    onTogglePreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = editorColors
    val activeTab = tabs.find { it.relativePath == activeTabPath }

    Row(
        modifier = modifier.fillMaxWidth().background(colors.tabInactive),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        ) {
            tabs.forEach { tab ->
                EditorTabChip(
                    tab = tab,
                    active = tab.relativePath == activeTabPath,
                    onSelect = { onTabSelected(tab.relativePath) },
                    onClose = { onTabClosed(tab.relativePath) },
                )
            }
        }

        // Top-right, like VS Code's preview toggle, and only for markdown.
        if (activeTab?.isMarkdown == true) {
            Icon(
                imageVector = if (activeTab.showPreview) Icons.Filled.Code else Icons.Filled.Visibility,
                contentDescription = if (activeTab.showPreview) "Show source" else "Show preview",
                tint = colors.gutterText,
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .size(PREVIEW_ICON_DP.dp)
                    .clickable(onClick = onTogglePreview),
            )
        }
    }
}

@Composable
private fun EditorTabChip(
    tab: EditorTab,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = editorColors
    val background by animateColorAsState(
        targetValue = if (active) colors.tabActive else colors.tabInactive,
        animationSpec = motionSpec(),
        label = "tab-background",
    )

    Row(
        modifier = Modifier
            .background(background)
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = tab.name,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) colors.plainText else colors.gutterText,
        )
        if (tab.isDirty) {
            Box(
                modifier = Modifier
                    .size(DIRTY_DOT_DP.dp)
                    .background(colors.plainText, CircleShape)
                    .clickable(onClick = onClose),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close ${tab.name}",
                tint = colors.gutterText,
                modifier = Modifier
                    .size(CLOSE_ICON_DP.dp)
                    .clickable(onClick = onClose),
            )
        }
    }
}

private const val PREVIEW_ICON_DP = 18
private const val DIRTY_DOT_DP = 8
private const val CLOSE_ICON_DP = 14
