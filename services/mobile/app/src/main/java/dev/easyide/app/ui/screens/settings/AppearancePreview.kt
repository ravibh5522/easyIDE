package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.easyide.app.R
import dev.easyide.app.ui.kit.CursorBlock
import dev.easyide.app.ui.kit.CursorStyle
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitGroup
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.KitToggle
import dev.easyide.app.ui.theme.SyntaxRole

/**
 * A live strip of what the properties change (properties.md 7): a project row, a list row with a
 * switch, a dialog and an editor snippet. Built from the kit primitives and read through `Kit.*`, so
 * a change to accent, density, corners, scale, font pairing, contrast or motif shows here the moment
 * the setting is written, exactly as it shows everywhere else. It is inert: nothing in it is a control.
 */
@Composable
internal fun AppearancePreview() {
    val space = Kit.space
    Column(
        Modifier.padding(start = space.l, end = space.l, top = space.l).clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(space.m),
    ) {
        KitGroup {
            KitRow(
                title = stringResource(R.string.appearance_preview_project),
                subtitle = stringResource(R.string.appearance_preview_project_detail),
                mono = true,
                selected = true,
                trailing = { KitTag(stringResource(R.string.appearance_preview_branch)) },
            )
            KitRow(
                title = stringResource(R.string.appearance_preview_row),
                subtitle = stringResource(R.string.appearance_preview_row_detail),
                trailing = { KitToggle(true, null) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(space.m), verticalAlignment = Alignment.Top) {
            PreviewDialog(Modifier.weight(1f))
            PreviewEditor(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PreviewDialog(modifier: Modifier) {
    val colors = Kit.colors
    val shape = RoundedCornerShape(Kit.radius.l)
    Column(
        modifier.background(colors.overlay, shape).border(Kit.hairline, colors.panelBorder, shape).padding(Kit.space.m),
        verticalArrangement = Arrangement.spacedBy(Kit.space.s),
    ) {
        BasicText(stringResource(R.string.appearance_preview_dialog_title), style = Kit.text.heading.copy(color = colors.plainText))
        BasicText(stringResource(R.string.appearance_preview_dialog_body), style = Kit.text.caption.copy(color = colors.textMuted))
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(Kit.space.xs, Alignment.End)) {
            KitButton(stringResource(R.string.action_cancel), {}, style = KitButtonStyle.Ghost)
            KitButton(stringResource(R.string.appearance_preview_confirm), {})
        }
    }
}

@Composable
private fun PreviewEditor(modifier: Modifier) {
    val colors = Kit.colors
    val shape = RoundedCornerShape(Kit.radius.m)
    val code = previewCode()
    Column(
        modifier.background(colors.background, shape).border(Kit.hairline, colors.panelBorder, shape).padding(Kit.space.m),
        verticalArrangement = Arrangement.spacedBy(Kit.space.xs),
    ) {
        code.forEach { BasicText(it, style = Kit.text.monoSmall.copy(color = colors.plainText), maxLines = 1) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("  ", style = Kit.text.monoSmall)
            CursorBlock(style = CursorStyle.Blinking)
        }
    }
}

@Composable
private fun previewCode(): List<AnnotatedString> {
    val syntax = Kit.colors.syntax
    return listOf(
        buildAnnotatedString {
            withStyle(SpanStyle(color = syntax[SyntaxRole.KEYWORD])) { append("fun ") }
            withStyle(SpanStyle(color = syntax[SyntaxRole.FUNCTION])) { append("main") }
            withStyle(SpanStyle(color = syntax[SyntaxRole.PUNCTUATION])) { append("() {") }
        },
        buildAnnotatedString {
            withStyle(SpanStyle(color = syntax[SyntaxRole.COMMENT])) { append("  // ok") }
        },
    )
}
