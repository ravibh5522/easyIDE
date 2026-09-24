package dev.easyide.app.ui.screens.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitSectionHeader
import dev.easyide.app.ui.kit.KitTabs
import dev.easyide.app.ui.kit.kitClampHeight
import dev.easyide.app.ui.kit.PromptGlyph
import dev.easyide.app.ui.kit.drawsSupporting

/**
 * The title line of a workspace panel (VS Code's side bar header): the caps title, then the panel's
 * toolbar buttons at the end. It is `tabHeight` tall and the buttons are all [KitIconButton]s of the
 * one toolbar size with no gap between their hit boxes, so they sit evenly; the title gives way to
 * them with an ellipsis. The panel's own content starts right under it.
 */
@Composable
internal fun PanelTitleRow(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    val space = Kit.space
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = Kit.control.tabHeight).padding(start = Kit.control.hPad, end = space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).semantics(mergeDescendants = true) { heading() },
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

/**
 * The tab row of a panel (Terminal, Problems, References, Outline): [KitTabs] that scroll when the labels
 * do not fit, then the panel's buttons, which keep their room and never sit under a label. Everything is
 * centred in `panelTabHeight`, so the tabs' rule and the buttons share one baseline.
 */
@Composable
internal fun PanelTabRow(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    val height = Kit.control.panelTabHeight
    Row(modifier.fillMaxWidth().defaultMinSize(minHeight = height), verticalAlignment = Alignment.CenterVertically) {
        KitTabs(labels, selected, onSelect, Modifier.weight(1f), height = height)
        Row(Modifier.kitClampHeight(height), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** The ids of a panel's sections the user closed; saved across rotation, so a closed section stays closed. */
internal class ClosedSections(private val state: MutableState<Set<String>>) {
    fun isOpen(id: String): Boolean = id !in state.value

    fun toggle(id: String) {
        state.value = if (id in state.value) state.value - id else state.value + id
    }
}

@Composable
internal fun rememberClosedSections(): ClosedSections = ClosedSections(rememberSaveable { mutableStateOf(emptySet<String>()) })

/**
 * A collapsible section of a panel's list: a pinned [KitSectionHeader] with the row [count] and the
 * section's [actions], then [rows] while it is open in [closed].
 */
internal fun LazyListScope.panelSection(
    closed: ClosedSections,
    id: String,
    title: String,
    count: Int,
    actions: (@Composable RowScope.() -> Unit)? = null,
    rows: LazyListScope.() -> Unit,
) {
    val open = closed.isOpen(id)
    stickyHeader(key = "section:$id") {
        KitSectionHeader(title, Modifier.background(Kit.colors.panel), count = count, expanded = open, onToggle = { closed.toggle(id) }, actions = actions)
    }
    if (open) rows()
}
