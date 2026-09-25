package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitChoice
import dev.easyide.app.ui.kit.KitField

private val SEARCH_KEYBOARD = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search)

/**
 * The search field and the sort choice on one line, both at the field height: the field takes what
 * the sort control leaves. A segmented control fills any width it is given, so the control is given
 * exactly the width of its labels.
 */
@Composable
internal fun SearchSortLine(query: String, sort: ProjectSort, onQueryChanged: (String) -> Unit, onSortChanged: (ProjectSort) -> Unit) {
    val labels = ProjectSort.entries.associateWith { it.label() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Kit.control.hPad, vertical = Kit.space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Kit.space.s),
    ) {
        KitField(query, onQueryChanged, Modifier.weight(1f), hint = stringResource(R.string.home_search_hint), keyboard = SEARCH_KEYBOARD)
        KitChoice(ProjectSort.entries, sort, { labels.getValue(it) }, onSortChanged, Modifier.width(segmentsWidth(labels.values.toList())))
    }
}

@Composable
private fun ProjectSort.label(): String = stringResource(
    when (this) {
        ProjectSort.RECENT -> R.string.home_sort_recent
        ProjectSort.NAME -> R.string.home_sort_name
    },
)

/** What a segmented control needs to show [labels] whole: each label in the tab type with the tab padding, and the border. */
@Composable
private fun segmentsWidth(labels: List<String>): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = Kit.text.title
    val pad = Kit.control.hPad * 2
    return remember(labels, style, density, pad) {
        val text = labels.sumOf { measurer.measure(it, style, maxLines = 1).size.width }
        with(density) { text.toDp() } + pad * labels.size + Kit.hairline * 2
    }
}
