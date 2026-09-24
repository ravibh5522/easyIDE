package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.PromptGlyph
import dev.easyide.app.ui.kit.drawsSupporting
import dev.easyide.app.ui.theme.tabular

/** A panel row (tree, change, problem) is the row token tall: 28dp dense, 36dp comfortable. */
@Composable
@ReadOnlyComposable
internal fun panelRowHeight(): Dp = Kit.control.rowHeight

/**
 * The title line of a workspace panel: caps section header led by the prompt glyph, then the
 * panel's header actions. The panel's own content starts right under it.
 */
@Composable
internal fun PanelTitleRow(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val space = Kit.space
    Row(
        modifier.fillMaxWidth().padding(start = Kit.control.hPad, end = space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.xs),
    ) {
        Row(
            Modifier.weight(1f).defaultMinSize(minHeight = Kit.control.tabHeight).semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(space.xs),
        ) {
            if (Kit.feel.motif.drawsSupporting) PromptGlyph()
            BasicText(
                title.uppercase(),
                style = Kit.text.label.copy(color = Kit.colors.textMuted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions()
    }
}

/** A list section inside a panel: caps title, mono count, and at most one bulk action ("Stage all"). */
@Composable
internal fun PanelGroupHeader(title: String, count: Int, modifier: Modifier = Modifier, bulk: KitAction? = null) {
    val space = Kit.space
    Row(
        modifier.fillMaxWidth().padding(start = space.m, end = space.xs, top = space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.s),
    ) {
        BasicText(
            title.uppercase(),
            Modifier.weight(1f, fill = false).semantics { heading() },
            style = Kit.text.label.copy(color = Kit.colors.textMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BasicText("$count", style = Kit.text.monoSmall.copy(color = Kit.colors.textMuted).tabular())
        if (bulk != null) KitButton(bulk.label, bulk.onClick, Modifier.weight(1f, fill = false), KitButtonStyle.Ghost)
    }
}
