package dev.easyide.app.ui.shell.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.data.settings.SettingsSchema
import dev.easyide.app.ui.foundation.LocalSettings
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitField
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.screens.workspace.PanelTitleRow
import dev.easyide.app.ui.screens.workspace.files.FileIcon
import dev.easyide.app.ui.screens.workspace.files.FileIndexer
import dev.easyide.app.ui.screens.workspace.files.PathHit
import dev.easyide.app.ui.screens.workspace.files.PathMatcher
import dev.easyide.sandbox.files.FileNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Search container: a name search over the project's files, the same index and ranking as Go to
 * File. A text search inside files needs a search engine the app does not have yet, so this is the
 * honest part of it; a pick opens the file as a document like any tap in the tree.
 */
@Composable
fun SearchPanel(env: WorkspaceEnv, modifier: Modifier = Modifier) {
    val files = env.editing.files
    val index by files.index.collectAsState()
    val indexing by files.indexing.collectAsState()
    val recent by files.recent.collectAsState()
    val hideHidden = LocalSettings.current[SettingsSchema.explorerHideHidden]
    var query by rememberSaveable { mutableStateOf("") }
    // Rebuilt when the panel opens: files may have changed since the last walk. The old list stays up meanwhile.
    LaunchedEffect(hideHidden) { files.refreshIndex(hideHidden) }
    val hits by produceState(emptyList<PathHit>(), query, index, recent) {
        value = withContext(Dispatchers.Default) { PathMatcher.rank(query, index.paths, recent.paths, MAX_RESULTS) }
    }
    Column(modifier.fillMaxSize().background(Kit.colors.panel)) {
        PanelTitleRow(stringResource(R.string.wshell_search_title))
        KitField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = Kit.control.hPad, vertical = Kit.space.xs), hint = stringResource(R.string.wshell_search_hint))
        val note = when {
            hits.isEmpty() && indexing -> stringResource(R.string.wshell_search_indexing)
            hits.isEmpty() -> stringResource(R.string.wshell_search_empty)
            index.truncated -> stringResource(R.string.wshell_search_truncated, FileIndexer.MAX_FILES)
            else -> null
        }
        if (hits.isEmpty()) KitRow(note.orEmpty(), enabled = false)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(hits, key = { it.path }) { hit ->
                KitRow(
                    title = hit.fileName,
                    subtitle = hit.directory.ifEmpty { null },
                    leading = { FileIcon(hit.fileName, size = Kit.control.rowIcon) },
                    onClick = { env.callbacks.onFileOpened(FileNode(hit.fileName, hit.path, isDirectory = false, sizeBytes = 0)) },
                    id = "search-hit",
                )
            }
            if (hits.isNotEmpty() && note != null) item { KitRow(note, enabled = false) }
        }
    }
}

private const val MAX_RESULTS = 100
