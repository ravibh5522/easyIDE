package dev.easyide.app.ui.shell.host

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.KitScaffold
import dev.easyide.app.ui.kit.kitTag
import dev.easyide.app.ui.shell.DocumentRegistry
import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.ShellState

/** What the stage can do to its group; the host wires these to the view model. */
class StageCallbacks(
    val onBack: () -> Unit,
    val onActivate: (DocumentUri) -> Unit,
    val onKeep: (DocumentUri) -> Unit,
    val onClose: (DocumentUri) -> Unit,
)

/**
 * The main stage with its one editor group. On a wide window that is a tab strip over the active
 * document; on a phone it is the document alone as a titled page whose back arrow steps Back
 * (to the list, or to the previous page of the same document). With nothing open, [idle] (the active
 * container's default content) fills the stage, else a hint. More groups arrive with R3/R6.
 */
@Composable
fun StageHost(
    state: ShellState,
    documents: DocumentRegistry,
    renderers: DocumentRendererRegistry,
    callbacks: StageCallbacks,
    modifier: Modifier = Modifier,
    idle: PanelRenderer? = null,
) {
    val group = state.current.stage.activeGroup
    val active = group.activeTab?.uri
    val body: @Composable (Modifier) -> Unit = { m ->
        if (active != null) {
            DocumentBody(active, documents, renderers, { callbacks.onClose(active) }, m)
        } else if (idle != null) {
            idle.Render(m)
        } else {
            KitEmptyState(EmptyArt.Prompt, stringResource(R.string.shell_stage_empty), m)
        }
    }
    if (state.compact) {
        KitScaffold(
            title = active?.let { renderers.titleOf(documents, it) }.orEmpty(),
            modifier = modifier.kitTag("stage").focusGroup(),
            onBack = callbacks.onBack,
        ) { inset -> body(Modifier.padding(inset)) }
        return
    }
    Column(modifier.kitTag("stage").fillMaxSize().background(Kit.colors.background).focusGroup()) {
        if (group.tabs.isNotEmpty()) {
            DocumentStrip(
                group,
                titleOf = { renderers.titleOf(documents, it.uri) },
                onActivate = { callbacks.onActivate(it.uri) },
                onKeep = { callbacks.onKeep(it.uri) },
                onClose = { callbacks.onClose(it.uri) },
            )
        }
        val sides = if (group.tabs.isEmpty()) WindowInsetsSides.Vertical else WindowInsetsSides.Bottom
        body(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(sides)))
    }
}
