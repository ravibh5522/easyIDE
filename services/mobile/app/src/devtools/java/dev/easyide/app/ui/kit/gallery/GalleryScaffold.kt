package dev.easyide.app.ui.kit.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.kit.kitGroupSeparator
import dev.easyide.app.ui.theme.sectionHeader

/**
 * One labelled specimen in a section's group: a muted caption above the primitive in one state,
 * a separator inset to the text edge. The caption is what a screenshot or a bug report names.
 */
@Composable
fun Sample(label: String, content: @Composable ColumnScope.() -> Unit) {
    val space = Kit.space
    Column(
        Modifier.fillMaxWidth().kitGroupSeparator(space.l).padding(horizontal = space.l, vertical = space.s),
        verticalArrangement = Arrangement.spacedBy(space.xs),
    ) {
        BasicText(label, style = Kit.type.labelMedium.copy(color = Kit.colors.textMuted))
        content()
    }
}

/** A caption made of two facts, such as a style and its state. */
@Composable
fun pairLabel(first: Int, second: Int): String =
    stringResource(R.string.gallery_pair, stringResource(first), stringResource(second))

/** Every tone with the string that names it, in the order the kit lists them. */
val TONE_LABELS: List<Pair<Tone, Int>> = listOf(
    Tone.Neutral to R.string.gallery_tone_neutral,
    Tone.Accent to R.string.gallery_tone_accent,
    Tone.Success to R.string.gallery_tone_success,
    Tone.Warning to R.string.gallery_tone_warning,
    Tone.Danger to R.string.gallery_tone_danger,
    Tone.Info to R.string.gallery_tone_info,
)

/** A labelled block that is not itself a group, for specimens that are groups (a group may not nest). */
@Composable
fun GalleryBlock(title: String, content: @Composable ColumnScope.() -> Unit) {
    val space = Kit.space
    Column(Modifier.fillMaxWidth().padding(start = space.l, end = space.l, top = space.l), verticalArrangement = Arrangement.spacedBy(space.s)) {
        BasicText(title.uppercase(), Modifier.semantics { heading() }, style = Kit.type.sectionHeader.copy(color = Kit.colors.textMuted))
        content()
    }
}

/** Fractions are shown as whole percents in captions. */
const val PERCENT = 100
