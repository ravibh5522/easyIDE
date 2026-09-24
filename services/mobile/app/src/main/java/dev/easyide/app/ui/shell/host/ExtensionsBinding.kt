package dev.easyide.app.ui.shell.host

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.easyide.app.ui.appViewModel
import dev.easyide.app.ui.screens.extensions.ExtensionPage
import dev.easyide.app.ui.screens.extensions.ExtensionsDialogs
import dev.easyide.app.ui.screens.extensions.ExtensionsPanel
import dev.easyide.app.ui.screens.extensions.ExtensionsViewModel
import dev.easyide.app.ui.screens.extensions.extensionName
import dev.easyide.app.ui.shell.DocumentUri

/*
 * Extensions on the shell: the installed / Browse list as the primary panel, one extension as the
 * document `easyide://extension/<id>`. The panel draws its own title row and install menu, and the
 * shell adds no second header around it.
 */

internal fun extensionsPanel(deps: ShellDeps) = panelRenderer { modifier ->
    val viewModel: ExtensionsViewModel = appViewModel(deps.factory)
    val shell = LocalShellActions.current
    ExtensionsPanel(
        selectedId = AppDocuments.extensionIdOf(LocalShellState.current.activeDocument),
        onSelect = { id -> DocumentUri.easyide("extension", id)?.let(shell.open) },
        viewModel = viewModel,
        modifier = modifier,
    )
}

internal fun extensionDocument(deps: ShellDeps) = documentRenderer(
    title = { uri ->
        val viewModel: ExtensionsViewModel = appViewModel(deps.factory)
        viewModel.uiState.collectAsStateWithLifecycle().value.extensionName(AppDocuments.extensionIdOf(uri))
    },
) { uri, modifier ->
    val viewModel: ExtensionsViewModel = appViewModel(deps.factory)
    val shell = LocalShellActions.current
    ExtensionPage(
        id = AppDocuments.extensionIdOf(uri).orEmpty(),
        viewModel = viewModel,
        // The page names its targets as `easyide://` text (its settings, for one); the shell opens them like any document.
        onOpenTarget = { text -> DocumentUri.parse(text)?.let(shell.open) },
        onClosed = shell.back,
        modifier = modifier,
    )
}

/** The install approval, rollback, create and Browse-detail dialogs, mounted once. */
@Composable
internal fun ExtensionsDialogsHost(deps: ShellDeps) {
    val viewModel: ExtensionsViewModel = appViewModel(deps.factory)
    ExtensionsDialogs(viewModel)
}
