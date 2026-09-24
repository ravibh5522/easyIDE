package dev.easyide.app.ui.kit

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.easyide.app.R
import dev.easyide.app.ui.theme.EasyIdeFonts

/** Box-drawing pictures for empty states; each is at most five lines (identity.md 10). */
enum class EmptyArt(@StringRes internal val text: Int) {
    Prompt(R.string.kit_art_prompt),
    Search(R.string.kit_art_search),
    Offline(R.string.kit_art_offline),
}

/**
 * Nothing to show yet: [art] (only at `appearance.motif` full), one sentence, and at most one
 * command-style [action] led by the prompt glyph. Left-aligned and plain, never a centred hero.
 */
@Composable
fun KitEmptyState(
    art: EmptyArt,
    message: String,
    modifier: Modifier = Modifier,
    action: KitAction? = null,
) {
    val colors = Kit.colors
    val space = Kit.space
    Column(modifier.fillMaxWidth().padding(space.l), verticalArrangement = Arrangement.spacedBy(space.s)) {
        if (Kit.feel.motif.drawsArt) {
            BasicText(
                stringResource(art.text),
                Modifier.clearAndSetSemantics { },
                style = Kit.type.bodySmall.copy(fontFamily = EasyIdeFonts.mono, color = colors.textDisabled),
            )
        }
        BasicText(message, style = Kit.type.bodyMedium.copy(color = colors.textMuted))
        if (action != null) {
            Row(
                Modifier.kitTouchFloor().kitTag("empty-action").kitPressable(action.onClick).padding(horizontal = space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(space.s),
            ) {
                if (Kit.feel.motif.drawsSupporting) PromptGlyph(color = colors.accent)
                BasicText(action.label, style = Kit.type.labelLarge.copy(color = colors.accent))
            }
        }
    }
}
