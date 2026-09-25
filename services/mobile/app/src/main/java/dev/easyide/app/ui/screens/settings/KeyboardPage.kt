package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.R
import dev.easyide.app.ui.commands.BindingConflict
import dev.easyide.app.ui.commands.BindingSource
import dev.easyide.app.ui.commands.EffectiveBinding
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone

/**
 * Keyboard shortcuts (customization.md sec 7.3): every effective binding with its layer, the
 * conflicts (same keys, conditions that can hold together; the later one wins), a filter, and
 * add/remove, which edit the active profile's keybindings.json. A page, not a dialog (screens.md 5).
 */
@Composable
internal fun KeyboardPage(viewModel: SettingsViewModel) {
    val state by viewModel.keybindings.state.collectAsStateWithLifecycle()
    KeyboardContent(state, viewModel.keybindings::add, viewModel.keybindings::remove, viewModel::openKeybindingsJson)
}

@Composable
internal fun KeyboardContent(
    state: KeybindingsScreenState?,
    onAdd: (key: String, command: String, whenText: String?) -> Unit,
    onRemove: (EffectiveBinding) -> Unit,
    onEditJson: () -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var conflictsOnly by rememberSaveable { mutableStateOf(false) }
    var shown by rememberSaveable { mutableIntStateOf(BINDINGS_PAGE) }

    val list = state?.list
    val conflicts = list?.conflicts.orEmpty()
    val inConflict = conflicts.flatMapTo(HashSet()) { listOf(it.winner, it.shadowed) }
    val matching = list?.bindings.orEmpty().filter { b ->
        (!conflictsOnly || b in inConflict) &&
            (query.isBlank() || b.binding.command.contains(query, ignoreCase = true) || b.keyText.contains(query, ignoreCase = true))
    }

    KitField(query, { query = it; shown = BINDINGS_PAGE }, Modifier.pageGutter(), hint = stringResource(R.string.keys_search_hint))
    // Wraps rather than clipping: at a large font the third action would otherwise leave the window.
    FlowRow(Modifier.pageGutter(), Arrangement.spacedBy(Kit.space.s), Arrangement.spacedBy(Kit.space.xs), itemVerticalAlignment = Alignment.CenterVertically) {
        KitTag(stringResource(R.string.keys_conflicts, conflicts.size), tone = if (conflicts.isEmpty()) Tone.Neutral else Tone.Danger, selected = conflictsOnly, onClick = { conflictsOnly = !conflictsOnly })
        KitButton(stringResource(R.string.keys_add), { adding = true }, style = KitButtonStyle.Secondary, enabled = state != null)
        KitButton(stringResource(R.string.keys_edit_json), onEditJson, style = KitButtonStyle.Ghost)
    }
    if (conflictsOnly && conflicts.isNotEmpty()) {
        KitSection(stringResource(R.string.keys_conflicts_section), count = conflicts.size, collapsible = true) { conflicts.forEach { ConflictRow(it) } }
    }
    if (matching.isEmpty()) {
        KitEmptyState(EmptyArt.Search, stringResource(R.string.keys_none))
    } else {
        KitSection(stringResource(R.string.settings_keyboard_shortcuts), count = matching.size, collapsible = true) {
            matching.take(shown).forEach { b -> BindingRow(b, b in inConflict) { onRemove(b) } }
        }
        if (matching.size > shown) {
            Row(Modifier.pageGutter()) { KitButton(stringResource(R.string.keys_show_more, matching.size - shown), { shown += BINDINGS_PAGE }, style = KitButtonStyle.Ghost) }
        }
    }
    if (adding) AddBindingDialog(state?.commands.orEmpty(), onAdd) { adding = false }
}

@Composable
private fun BindingRow(b: EffectiveBinding, conflicting: Boolean, onRemove: () -> Unit) {
    val details = listOfNotNull(sourceLabel(b), b.conditionText?.let { stringResource(R.string.keys_when, it) })
    // A phone has no room for the key beside a full command id: the key moves to the description line.
    val narrow = LocalRowLayout.current.narrow
    KitRow(
        title = b.binding.command,
        subtitle = (if (narrow) listOf(b.keyText) + details else details).joinToString(" - "),
        mono = true,
        secondLine = true,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
                if (conflicting) KitTag(stringResource(R.string.keys_conflict_tag), tone = Tone.Danger)
                if (!narrow) KitTag(b.keyText)
                KitIconButton(
                    Icons.Filled.Delete,
                    stringResource(if (b.source == BindingSource.USER) R.string.keys_remove else R.string.keys_remove_default),
                    onRemove,
                )
            }
        },
    )
}

@Composable
private fun ConflictRow(c: BindingConflict) {
    KitRow(
        title = c.winner.keyText,
        subtitle = stringResource(R.string.keys_conflict_detail, c.winner.binding.command, sourceLabel(c.winner), c.shadowed.binding.command, sourceLabel(c.shadowed)),
        mono = true,
    )
}

@Composable
internal fun sourceLabel(b: EffectiveBinding): String = when (b.source) {
    BindingSource.BUILT_IN -> stringResource(R.string.keys_source_builtin)
    BindingSource.EXTENSION -> stringResource(R.string.keys_source_extension, b.owner.orEmpty())
    BindingSource.USER -> stringResource(R.string.keys_source_user)
}

/** Bindings drawn per step; the table is not lazy inside a scrolling page, so it grows on request. */
private const val BINDINGS_PAGE = 50
