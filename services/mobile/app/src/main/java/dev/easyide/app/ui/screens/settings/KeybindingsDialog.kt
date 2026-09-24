package dev.easyide.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.easyide.app.R
import dev.easyide.app.ui.commands.BindingConflict
import dev.easyide.app.ui.commands.BindingSource
import dev.easyide.app.ui.commands.EffectiveBinding
import dev.easyide.app.ui.commands.KeyFocus
import dev.easyide.app.ui.commands.KeyNames
import dev.easyide.app.ui.theme.Spacing

/**
 * Keyboard Shortcuts (customization.md sec 7.3): every effective binding with its layer,
 * the conflicts (same keys, conditions that can hold together; the later one wins), a
 * filter, and add/remove, which edit the active profile's keybindings.json.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeybindingsDialog(state: KeybindingsScreenState?, controller: KeybindingsController, onEditJson: () -> Unit, onClose: () -> Unit) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var conflictsOnly by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.keys_screen_title)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close)) }
                    },
                    actions = {
                        TextButton(onClick = onEditJson) { Text(stringResource(R.string.keys_edit_json)) }
                        IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.keys_add)) }
                    },
                )
            },
        ) { padding ->
            val list = state?.list
            val conflicts = list?.conflicts.orEmpty()
            val inConflict = conflicts.flatMapTo(HashSet()) { listOf(it.winner, it.shadowed) }
            val shown = list?.bindings.orEmpty().filter { b ->
                (!conflictsOnly || b in inConflict) &&
                    (query.isBlank() || b.binding.command.contains(query, ignoreCase = true) || b.keyText.contains(query, ignoreCase = true))
            }
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text(stringResource(R.string.keys_search_hint)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
                    )
                }
                item {
                    FilterChip(
                        selected = conflictsOnly, onClick = { conflictsOnly = !conflictsOnly },
                        label = { Text(stringResource(R.string.keys_conflicts, conflicts.size)) },
                        modifier = Modifier.padding(horizontal = Spacing.l),
                    )
                }
                if (conflicts.isNotEmpty() && conflictsOnly) {
                    items(conflicts) { ConflictRow(it) }
                    item { HorizontalDivider() }
                }
                items(shown) { b -> BindingRow(b, b in inConflict) { controller.remove(b) } }
            }
        }
    }
    if (adding) AddBindingDialog(state?.commands.orEmpty(), controller::add) { adding = false }
}

@Composable
private fun BindingRow(b: EffectiveBinding, conflicting: Boolean, onRemove: () -> Unit) {
    ListItem(
        headlineContent = { Text(b.binding.command) },
        overlineContent = { Text(b.keyText, style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace)) },
        supportingContent = {
            Column {
                Text(sourceLabel(b), style = MaterialTheme.typography.bodySmall)
                b.conditionText?.let { Text(stringResource(R.string.keys_when, it), style = MaterialTheme.typography.bodySmall) }
                if (conflicting) Text(stringResource(R.string.keys_in_conflict), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(if (b.source == BindingSource.USER) R.string.keys_remove else R.string.keys_remove_default))
            }
        },
    )
}

@Composable
private fun ConflictRow(c: BindingConflict) {
    ListItem(
        headlineContent = { Text(c.winner.keyText, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)) },
        supportingContent = {
            Text(stringResource(R.string.keys_conflict_detail, c.winner.binding.command, sourceLabel(c.winner), c.shadowed.binding.command, sourceLabel(c.shadowed)))
        },
    )
}

@Composable
private fun sourceLabel(b: EffectiveBinding): String = when (b.source) {
    BindingSource.BUILT_IN -> stringResource(R.string.keys_source_builtin)
    BindingSource.EXTENSION -> stringResource(R.string.keys_source_extension, b.owner.orEmpty())
    BindingSource.USER -> stringResource(R.string.keys_source_user)
}

/** Key text is validated with [KeyNames]; the command may be typed or picked from the known ids. */
@Composable
private fun AddBindingDialog(commands: List<String>, onAdd: (String, String, String?) -> Unit, onDismiss: () -> Unit) {
    var key by rememberSaveable { mutableStateOf("") }
    var command by rememberSaveable { mutableStateOf("") }
    var focus by rememberSaveable { mutableStateOf(KeyFocus.ANYWHERE) }
    val keyValid = KeyNames.parseSequence(key) != null
    val matches = remember(command, commands) {
        if (command.isBlank()) emptyList() else commands.filter { it.contains(command, ignoreCase = true) && it != command }.take(MAX_SUGGESTIONS)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_add)) },
        text = {
            Column {
                OutlinedTextField(key, { key = it }, label = { Text(stringResource(R.string.keys_form_key)) }, singleLine = true,
                    isError = key.isNotBlank() && !keyValid, supportingText = { Text(stringResource(R.string.keys_form_key_hint)) },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(command, { command = it }, label = { Text(stringResource(R.string.keys_form_command)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Column(modifier = Modifier.heightIn(max = SUGGESTIONS_HEIGHT_DP.dp)) {
                    matches.forEach { id ->
                        Text(id, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().clickable { command = id }.padding(vertical = Spacing.xxs))
                    }
                }
                Text(stringResource(R.string.keys_form_focus), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = Spacing.s))
                KeyFocus.entries.forEach { f ->
                    FilterChip(selected = focus == f, onClick = { focus = f }, label = { Text(focusLabel(f)) })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(key, command, focus.whenText); onDismiss() }, enabled = keyValid && command.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun focusLabel(f: KeyFocus): String = stringResource(when (f) {
    KeyFocus.ANYWHERE -> R.string.keys_focus_anywhere
    KeyFocus.OUTSIDE_TERMINAL -> R.string.keys_focus_outside_terminal
    KeyFocus.TERMINAL_ONLY -> R.string.keys_focus_terminal
})

private const val MAX_SUGGESTIONS = 6
private const val SUGGESTIONS_HEIGHT_DP = 160
