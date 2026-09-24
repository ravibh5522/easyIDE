package dev.easyide.app.ui.screens.extensions

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.KitScaffold

/**
 * The Extensions screen while the navigation graph still routes to it: a frame around
 * [ExtensionsPanel] and, once a row is picked, [ExtensionPage]. The shell hosts those two
 * directly (panel and document) and this screen goes when its route does.
 */
@Composable
fun ExtensionsScreen(viewModel: ExtensionsViewModel, onBack: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val open = selected
    BackHandler(enabled = open != null) { selected = null }
    KitScaffold(title = open ?: stringResource(R.string.ext_screen_title), onBack = { if (open != null) selected = null else onBack() }) {
        if (open == null) {
            ExtensionsPanel(selectedId = null, onSelect = { selected = it }, viewModel = viewModel)
        } else {
            ExtensionPage(open, viewModel, onOpenTarget = null, onClosed = { selected = null })
        }
    }
    ExtensionsDialogs(viewModel)
}
