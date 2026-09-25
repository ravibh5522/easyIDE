package dev.easyide.app.ui.screens.workspace.git

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.theme.IconSize
import dev.easyide.sandbox.git.GitRef
import dev.easyide.sandbox.git.GitRefKind

/** WCAG contrast of two colours, 1 to 21. */
internal fun contrast(a: Color, b: Color): Float {
    val hi = maxOf(a.luminance(), b.luminance())
    val lo = minOf(a.luminance(), b.luminance())
    return (hi + CONTRAST_OFFSET) / (lo + CONTRAST_OFFSET)
}

private const val CONTRAST_OFFSET = 0.05f

/** Whichever of the two text colours reads better on [fill]: the lane colours are mid tones, so it is the dark one on most. */
internal fun readableOn(fill: Color, dark: Color, light: Color): Color =
    if (contrast(fill, dark) >= contrast(fill, light)) dark else light

/** One chip: a ref, with [synced] set on a local branch whose remote-tracking twin sits on the same commit and is folded into it. */
internal data class RefChipModel(val name: String, val kind: GitRefKind, val current: Boolean, val synced: Boolean)

/**
 * The chips of a commit. A local branch and its remote twin on the same commit (`main` and `origin/main`) are
 * one chip with the cloud, since the pair only says "in sync" and a narrow row cannot afford two names.
 */
internal fun chipModels(refs: List<GitRef>): List<RefChipModel> {
    val remotes = refs.filter { it.kind == GitRefKind.REMOTE }.map { it.name }
    val twinned = HashSet<String>()
    val out = ArrayList<RefChipModel>(refs.size)
    for (ref in refs) {
        if (ref.kind != GitRefKind.LOCAL) continue
        val twin = remotes.firstOrNull { it.substringAfter('/') == ref.name }
        if (twin != null) twinned += twin
        out += RefChipModel(ref.name, ref.kind, ref.isCurrent, synced = twin != null)
    }
    refs.filter { it.kind == GitRefKind.REMOTE && it.name !in twinned }.forEach { out += RefChipModel(it.name, it.kind, false, false) }
    refs.filter { it.kind == GitRefKind.TAG }.forEach { out += RefChipModel(it.name, it.kind, false, false) }
    return out
}

/** The chips to draw and how many are folded into a count: a commit with many branches keeps its row one line. */
internal fun visibleChips(chips: List<RefChipModel>, shown: Int = GitUi.REF_CHIPS_SHOWN): Pair<List<RefChipModel>, Int> =
    if (chips.size <= shown) chips to 0 else chips.take(shown) to chips.size - shown

/**
 * A commit's refs as fully rounded pills in its lane colour with dark text, ellipsized: a remote-tracking chip
 * (or a local one in sync with it) wears a cloud, a tag a tag glyph, the checked-out branch a ring. What does
 * not fit the shown count is a "+n" pill.
 */
@Composable
internal fun RefChips(refs: List<GitRef>, lane: Color, modifier: Modifier = Modifier) {
    if (refs.isEmpty()) return
    val (shown, more) = visibleChips(chipModels(refs))
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Kit.space.xs), verticalAlignment = Alignment.CenterVertically) {
        shown.forEach { RefChip(it, lane, Modifier.weight(1f, fill = false)) }
        if (more > 0) RefPill("+$more", lane, null, false, Modifier)
    }
}

@Composable
private fun RefChip(chip: RefChipModel, lane: Color, modifier: Modifier) {
    val cd = when {
        chip.synced -> stringResource(R.string.gitui_ref_synced_cd, chip.name)
        chip.kind == GitRefKind.LOCAL -> stringResource(R.string.gitui_ref_branch_cd, chip.name)
        chip.kind == GitRefKind.REMOTE -> stringResource(R.string.gitui_ref_remote_cd, chip.name)
        else -> stringResource(R.string.gitui_ref_tag_cd, chip.name)
    }
    val icon = when {
        chip.synced || chip.kind == GitRefKind.REMOTE -> Icons.Filled.Cloud
        chip.kind == GitRefKind.TAG -> Icons.Filled.LocalOffer
        else -> null
    }
    RefPill(chip.name, lane, icon, chip.current, modifier.semantics { contentDescription = cd })
}

@Composable
private fun RefPill(text: String, lane: Color, icon: androidx.compose.ui.graphics.vector.ImageVector?, current: Boolean, modifier: Modifier) {
    val colors = Kit.colors
    val ink = readableOn(lane, colors.background, colors.plainText)
    Row(
        modifier
            .clip(FULLY_ROUND)
            .background(lane)
            .then(if (current) Modifier.border(Kit.hairline * 2, colors.plainText, FULLY_ROUND) else Modifier)
            .padding(horizontal = Kit.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Kit.space.xxs),
    ) {
        icon?.let { Image(it, null, Modifier.size(IconSize.xs), colorFilter = ColorFilter.tint(ink)) }
        BasicText(text, style = Kit.text.caption.copy(color = ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Half the shorter side on every corner: a pill for a chip, a disc for a dot. */
private val FULLY_ROUND = RoundedCornerShape(percent = 50)
