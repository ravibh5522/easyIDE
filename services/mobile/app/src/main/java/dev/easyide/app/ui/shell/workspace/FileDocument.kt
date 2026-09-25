package dev.easyide.app.ui.shell.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.IntOffset
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.app.ui.screens.workspace.EditorPane
import dev.easyide.app.ui.screens.workspace.ext.ContributedMenu
import dev.easyide.app.ui.screens.workspace.ext.sections
import dev.easyide.app.ui.screens.workspace.find.FindBar
import dev.easyide.app.ui.screens.workspace.lsp.LspEditorOverlay
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.host.documentRenderer
import dev.easyide.extensions.contrib.MenuIds

/** The renderer of `file:` documents: the existing editor, bound to the buffer of that path in the workspace view model. */
internal fun fileDocument() = documentRenderer(
    title = { uri -> FileDocuments.pathOf(uri)?.substringAfterLast('/') },
) { uri, modifier -> FileEditor(uri, modifier) }

@Composable
private fun FileEditor(uri: DocumentUri, modifier: Modifier) {
    val env = LocalWorkspaceEnv.current
    val path = FileDocuments.pathOf(uri) ?: return
    val overlays by env.lsp.semanticTokens.overlays.collectAsState()
    // Where the pointer was pressed, so the context menu opens under it; null while closed.
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    Column(modifier) {
        EditorBreadcrumbs(env, path)
        Box(Modifier.fillMaxWidth().weight(1f).onFocusChanged { env.actions.onEditorFocus(it.hasFocus) }) {
            EditorPane(
                tab = env.ui.openTabs.find { it.relativePath == path },
                onContentChanged = { env.callbacks.onContentChanged(path, it) },
                decorations = env.decorations.model(path),
                onGutterTap = { line -> env.lsp.onGutterTap(path, line) },
                overlay = { geometry -> LspEditorOverlay(env.lsp, path, geometry) },
                interaction = env.lsp,
                selections = env.selections,
                scrolls = env.session.scrolls,
                onSecondaryClick = { menuAt = it },
                semanticTokens = overlays[path],
                session = env.editing.session,
            )
            // Find works on the workspace's active tab, so only that document carries the widget.
            if (env.editing.find.isOpen && env.ui.activeTabPath == path) {
                FindBar(env.editing.find, env.actions.onFindFieldFocus, Modifier.align(Alignment.TopEnd), LocalWindowSize.current.width.isCompact)
            }
            ContributedMenu(
                expanded = menuAt != null,
                sections = env.contributions.menu(MenuIds.EDITOR_CONTEXT, env.commands).sections(),
                onRun = { env.contributions.run(it) },
                onDismiss = { menuAt = null },
                at = menuAt,
            )
        }
    }
}
