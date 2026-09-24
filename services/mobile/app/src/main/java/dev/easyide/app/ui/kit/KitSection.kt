package dev.easyide.app.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.easyide.app.ui.theme.sectionHeader

/**
 * A titled block of a page: an 11sp caps header led by the prompt glyph, the [content] in a
 * [KitGroup], and an optional description under it. Owns the page gutter and the space above,
 * so a page is a plain column of sections. A null [title] omits the header.
 */
@Composable
fun KitSection(
    title: String?,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Kit.colors
    val space = Kit.space
    Column(modifier.fillMaxWidth().padding(start = space.l, end = space.l, top = space.l)) {
        if (title != null) {
            Row(
                Modifier.padding(start = space.xs, bottom = space.s).semantics(mergeDescendants = true) { heading() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(space.xs),
            ) {
                if (Kit.feel.motif.drawsSupporting) PromptGlyph()
                BasicText(title.uppercase(), style = Kit.type.sectionHeader.copy(color = colors.textMuted))
            }
        }
        KitGroup(content = content)
        if (description != null) {
            BasicText(
                description,
                Modifier.padding(start = space.xs, top = space.xs),
                style = Kit.type.bodySmall.copy(color = colors.textMuted),
            )
        }
    }
}
