package dev.easyide.app.ui.shell.host

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import dev.easyide.app.ui.shell.ShellScope
import dev.easyide.app.ui.shell.ShellState
import dev.easyide.app.ui.shell.SplitAxis

/**
 * What the stage can do to its groups; the host wires these to the shell. [onSplit] and [onUnsplit]
 * are null where a stage cannot be split (app scope), which is also what hides their buttons.
 */
class StageCallbacks(
    val onBack: () -> Unit,
    val onActivate: (group: Int, uri: DocumentUri) -> Unit,
    val onKeep: (group: Int, uri: DocumentUri) -> Unit,
    val onClose: (group: Int, uri: DocumentUri) -> Unit,
    val onFocusGroup: (Int) -> Unit = {},
    val onSplit: (() -> Unit)? = null,
    val onUnsplit: ((Int) -> Unit)? = null,
    val isDirty: (DocumentUri) -> Boolean = { false },
)

/**
 * The main stage. In app scope on a phone it is one document as a titled page whose back arrow steps
 * Back (to the list, or to the previous page of the same document). Otherwise it is the scope's editor
 * groups along the stage's axis: each a tab strip over the active document on a wide window, the
 * document switcher over it on a phone. A group with nothing open shows [idle] (the active container's
 * default content, or the workspace's welcome page), else a hint. [trailing] is the active group's
 * actions (an editor's title actions); [emptyTitle] titles a phone's header while nothing is open.
 */
@Composable
fun StageHost(
    state: ShellState,
    documents: DocumentRegistry,
    renderers: DocumentRendererRegistry,
    callbacks: StageCallbacks,
    modifier: Modifier = Modifier,
    idle: PanelRenderer? = null,
    emptyTitle: String = "",
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val stage = state.current.stage
    if (state.compact && state.scope == ShellScope.APP) {
        val active = stage.activeGroup.activeTab?.uri
        KitScaffold(
            title = active?.let { renderers.titleOf(documents, it) }.orEmpty(),
            modifier = modifier.kitTag("stage").focusGroup(),
            onBack = callbacks.onBack,
        ) { inset -> StageBody(active, documents, renderers, idle, { active?.let { callbacks.onClose(stage.active, it) } }, Modifier.padding(inset)) }
        return
    }
    val visible = if (state.compact) listOf(stage.active) else stage.groups.indices.toList()
    val group: @Composable (Int, Modifier) -> Unit = { i, m ->
        val canSplit = i == stage.active && stage.groups.size < state.groupCapacity
        StageGroup(
            GroupView(stage.groups[i], i, i == stage.active, state.compact, stage.groups.size > 1, stage.axis),
            documents, renderers, idle, emptyTitle, GroupCallbacks(callbacks, i, canSplit),
            if (i == stage.active) trailing else ({}), m,
        )
    }
    val frame = modifier.kitTag("stage").fillMaxSize().background(Kit.colors.background)
    if (stage.axis == SplitAxis.ROW) {
        Row(frame) { visible.forEach { group(it, Modifier.weight(1f).fillMaxHeight()) } }
    } else {
        Column(frame) { visible.forEach { group(it, Modifier.weight(1f).fillMaxWidth()) } }
    }
}

/** The body of one document, or the idle content, or the empty hint. */
@Composable
internal fun StageBody(
    active: DocumentUri?,
    documents: DocumentRegistry,
    renderers: DocumentRendererRegistry,
    idle: PanelRenderer?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        active != null -> DocumentBody(active, documents, renderers, onClose, modifier)
        idle != null -> idle.Render(modifier)
        else -> KitEmptyState(EmptyArt.Prompt, stringResource(R.string.shell_stage_empty), modifier)
    }
}
