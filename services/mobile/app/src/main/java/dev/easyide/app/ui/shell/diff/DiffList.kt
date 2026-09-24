package dev.easyide.app.ui.shell.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.screens.workspace.git.GitUi
import dev.easyide.app.ui.theme.EasyIdeFonts
import dev.easyide.sandbox.git.DiffHunk

/** What the list needs to draw one diff: the laid-out [model], its [paint], and the hunk actions if the provider allows any. */
class DiffListSpec(val model: DiffModel, val paint: DiffPaint, val actions: HunkActions?, val busy: Boolean)

/**
 * The lines of one text diff, virtualised. Unified rows share one horizontal scroll, and side by side
 * has one per column, so a long line moves its column and the numbers stay put. Each hunk starts with its
 * header, which carries the hunk's actions.
 */
@Composable
internal fun DiffList(spec: DiffListSpec, state: LazyListState, modifier: Modifier = Modifier) {
    val leftScroll = rememberScrollState()
    val rightScroll = rememberScrollState()
    LazyColumn(modifier.fillMaxSize(), state) {
        items(spec.model.items) { item ->
            when (item) {
                is DiffItem.Header -> HunkHeader(item.hunk, spec)
                is DiffItem.Unified -> UnifiedLine(item.row, spec.paint, leftScroll)
                is DiffItem.Split -> Row(Modifier.fillMaxWidth()) {
                    SplitHalf(item.row.left, spec.paint, oldSide = true, scroll = leftScroll, modifier = Modifier.weight(1f))
                    Box(Modifier.width(GitUi.dividerWidth).background(Kit.colors.panelBorder))
                    SplitHalf(item.row.right, spec.paint, oldSide = false, scroll = rightScroll, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HunkHeader(hunk: DiffHunk, spec: DiffListSpec) {
    val colors = Kit.colors
    Row(
        Modifier.fillMaxWidth().background(colors.raised).padding(start = Kit.space.m, end = Kit.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            hunk.header,
            Modifier.weight(1f),
            style = Kit.type.labelSmall.copy(fontFamily = EasyIdeFonts.mono, color = colors.textMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        spec.actions?.available?.forEach { action ->
            KitButton(stringResource(action.label()), { spec.actions.perform(action, hunk) }, style = KitButtonStyle.Ghost, enabled = !spec.busy)
        }
    }
}

private fun HunkAction.label(): Int = when (this) {
    HunkAction.STAGE -> R.string.git_hunk_stage
    HunkAction.UNSTAGE -> R.string.git_hunk_unstage
    HunkAction.DISCARD -> R.string.git_hunk_discard
}
