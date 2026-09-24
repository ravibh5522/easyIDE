package dev.easyide.app.ui.screens.workspace

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import dev.easyide.app.R
import dev.easyide.app.ui.foundation.motionSpec
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.Stroke
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
    /** Contributed `editor/title` actions, drawn after the built-in ones. */
    actions: @Composable () -> Unit = {},
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
                tint = colors.textMuted,
                modifier = Modifier
                    .padding(horizontal = Spacing.m)
                    .size(IconSize.m)
                    .clickable(onClick = onTogglePreview),
            )
        }
        actions()
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
            .tabAccentBar(active, colors.tabActiveBorder)
            .clickable(onClick = onSelect)
            .padding(start = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tab.name,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) colors.tabActiveText else colors.tabInactiveText,
        )
        // The glyph stays small but the target is the 48dp minimum, which also
        // sets the tab's height - an 8dp dot was too easy to hit by accident.
        val closeLabel = stringResource(
            if (tab.isDirty) R.string.editor_close_unsaved_tab else R.string.editor_close_tab,
            tab.name,
        )
        Box(
            modifier = Modifier
                .clickable(onClick = onClose)
                .minimumInteractiveComponentSize()
                .semantics { contentDescription = closeLabel },
            contentAlignment = Alignment.Center,
        ) {
            if (tab.isDirty) {
                Box(modifier = Modifier.size(Spacing.s).background(colors.plainText, CircleShape))
            } else {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(IconSize.xs),
                )
            }
        }
    }
}

/**
 * The accent bar across the top of the active tab - the one place the accent
 * marks which tab has focus. Drawn, not laid out, so switching tabs never
 * shifts a label. Shared by the editor and terminal tab strips.
 */
internal fun Modifier.tabAccentBar(active: Boolean, color: Color): Modifier =
    if (!active) this else drawBehind {
        drawRect(color, size = Size(size.width, Stroke.accentBar.toPx()))
    }
