package dev.easyide.app.ui.shell.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.easyide.app.data.settings.ChromeSettingsSchema
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.ui.foundation.LocalSettings
import dev.easyide.app.ui.foundation.LocalWindowSize
import dev.easyide.lsp.protocol.LspFeature
import dev.easyide.sandbox.files.FileNode

/**
 * The breadcrumbs of one file document: its path, and the symbols holding the caret when a language
 * server offers document symbols (the outline the Outline panel shows, refreshed here on open and on
 * save). Off with `breadcrumbs.enabled`, and hidden on a window of compact height, where a row of
 * chrome costs more than it tells.
 */
@Composable
internal fun EditorBreadcrumbs(env: WorkspaceEnv, path: String) {
    val settings = LocalSettings.current
    if (!settings[ChromeSettingsSchema.breadcrumbs] || LocalWindowSize.current.height.isCompact) return
    val tab = env.ui.openTabs.find { it.relativePath == path }
    val active = env.ui.activeTabPath == path
    val facts by env.lsp.languageFacts.collectAsState()
    val outlineServed = active && facts.values.any { LspFeature.DOCUMENT_SYMBOL in it.features }
    val navigation = env.lsp.navigation
    LaunchedEffect(path, outlineServed, tab?.savedContent) { if (outlineServed) navigation.refreshOutline() }
    val outline by navigation.outline.collectAsState()
    val index by env.editing.files.index.collectAsState()
    val symbols = if (outlineServed && tab != null) BreadcrumbModel.symbolsAt(outline.filter { it.location.projectPath == path }, tab.content, env.selections[path].start) else emptyList()
    val hideHidden = settings[SettingsSchema.explorerHideHidden]
    Breadcrumbs(
        path = path,
        symbols = symbols,
        children = { dir -> BreadcrumbModel.children(index.paths, dir) },
        onCrumb = { env.editing.files.refreshIndex(hideHidden) },
        onOpenFile = { file -> env.callbacks.onFileOpened(FileNode(file.substringAfterLast('/'), file, isDirectory = false, sizeBytes = 0)) },
        onSymbol = { env.lsp.navigate(it.location) },
    )
}
