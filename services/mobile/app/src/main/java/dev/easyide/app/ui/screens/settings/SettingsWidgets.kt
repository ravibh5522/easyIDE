package dev.easyide.app.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.easyide.app.R
import dev.easyide.app.data.settings.LayerId
import dev.easyide.app.ui.theme.Spacing
import dev.easyide.app.ui.theme.sectionHeader

/**
 * The field keeps its own text while editing: each keystroke is written to the
 * store, but echoing the stored value back would arrive a frame late and move
 * the cursor. [isValid] marks the field in error without blocking typing.
 */
@Composable
fun SettingTextField(
    initial: String,
    singleLine: Boolean,
    enabled: Boolean,
    isValid: (String) -> Boolean = { true },
    onChange: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; if (isValid(it)) onChange(it) },
        singleLine = singleLine,
        enabled = enabled,
        isError = !isValid(text),
        modifier = Modifier.fillMaxWidth(),
    )
}

@StringRes
fun layerLabel(layer: LayerId): Int = when (layer) {
    LayerId.BUILT_IN -> R.string.settings_layer_default
    LayerId.EXTENSION -> R.string.settings_layer_extension
    LayerId.USER -> R.string.settings_layer_user
    LayerId.ENVIRONMENT -> R.string.settings_layer_environment
    LayerId.PROJECT -> R.string.settings_layer_project
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.sectionHeader,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.contentWidth().padding(start = Spacing.l, top = Spacing.l, bottom = Spacing.xs),
    )
}

/** Keeps settings rows readable instead of stretching across a wide tablet. */
fun Modifier.contentWidth(): Modifier = this.fillMaxWidth().widthIn(max = MAX_CONTENT_WIDTH)

private val MAX_CONTENT_WIDTH = 720.dp
