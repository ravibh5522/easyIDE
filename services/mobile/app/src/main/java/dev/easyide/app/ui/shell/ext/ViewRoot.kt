package dev.easyide.app.ui.shell.ext

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.ui.kit.EmptyArt
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.kit.KitEmptyState
import dev.easyide.app.ui.kit.Tone
import dev.easyide.app.ui.shell.host.LocalShellActions
import dev.easyide.extensions.view.PlanEnv
import dev.easyide.extensions.view.PlanNode
import dev.easyide.extensions.view.PlanResult
import dev.easyide.extensions.view.PlannedAction
import dev.easyide.extensions.view.ResolvedAction
import dev.easyide.extensions.view.ViewData
import dev.easyide.extensions.view.ViewDocument
import dev.easyide.extensions.view.ViewPlan
import dev.easyide.extensions.view.ViewType
import dev.easyide.extensions.view.ViewUiState
import dev.easyide.extensions.whenclause.ContextLookup
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What a view surface needs from the app: where data lives, how events run, the shell's context keys for `when`, and the log. */
class ExtViewHost(
    val hub: ViewDataHub,
    val events: ViewEvents,
    val context: StateFlow<ContextLookup>,
    val log: (owner: String, message: String) -> Unit,
    val now: () -> Long = System::currentTimeMillis,
)

/** What node composables read: fire an event, write a bound value, and the view's UI-local state. */
internal class ViewCtx(
    val fire: (PlannedAction, Map<String, JsonElement>) -> Unit,
    val commit: (path: String, value: JsonElement) -> Unit,
    val ui: ViewUiState,
    val setUi: (ViewUiState) -> Unit,
)

internal val LocalViewCtx = staticCompositionLocalOf<ViewCtx> { error("no extension view around this composable") }

/**
 * One extension view or document body drawn from its [doc] (extension-ui.md section 4). The data is [key]'s in the hub
 * (the view's `state` until something writes), [seed] adds what only this instance knows (a document's key). Each
 * change plans the tree again (a pure step, see `ViewPlan`); a view the plan refuses shows why instead of failing, and
 * an event asks first when its binding carries a `confirm`.
 */
@Composable
fun ExtViewSurface(
    doc: ViewDocument,
    key: String,
    owner: String,
    title: String,
    host: ExtViewHost,
    modifier: Modifier = Modifier,
    seed: JsonObject = JsonObject(emptyMap()),
) {
    val initial = remember(doc, seed) { JsonObject(doc.state + seed) }
    val data by remember(key, initial) { host.hub.flow(key, initial) }.collectAsState(initial)
    val context by host.context.collectAsState()
    var ui by remember(key) { mutableStateOf(ViewUiState()) }
    val logged = remember(key) { HashSet<String>() }
    val plan = remember(doc, data, ui, context) {
        ViewPlan.build(doc, data, PlanEnv(host.now(), context, ui) { if (logged.add(it)) host.log(owner, it) })
    }
    val scope = rememberCoroutineScope()
    val shell = LocalShellActions.current
    val failedText = stringResource(R.string.extview_action_failed)
    var pending by remember { mutableStateOf<ResolvedAction?>(null) }
    val run: (ResolvedAction) -> Unit = { action ->
        scope.launch { host.events.run(owner, key, initial, title, action) { what, reason -> shell.notify(failedText.format(what, reason)) } }
    }
    val ctx = remember(key, initial, ui) {
        ViewCtx(
            fire = { planned, local ->
                val resolved = planned.resolve(local)
                if (resolved.confirm != null) pending = resolved else run(resolved)
            },
            commit = { path, value -> host.hub.edit(key, initial) { ViewData.set(it, path, value) } },
            ui = ui,
            setUi = { ui = it },
        )
    }
    val unavailable = (plan as? PlanResult.Unavailable)?.reason
    LaunchedEffect(unavailable) { if (unavailable != null) host.log(owner, "view $key unavailable: $unavailable") }

    CompositionLocalProvider(LocalViewCtx provides ctx) {
        when (plan) {
            is PlanResult.Ready -> PlanNodeView(plan.root, modifier.fillMaxSize(), bounded = true, scroll = !plan.root.fills)
            is PlanResult.Unavailable -> KitEmptyState(EmptyArt.Offline, stringResource(R.string.extview_unavailable, plan.reason), modifier)
        }
    }
    pending?.let { action ->
        val confirm = requireNotNull(action.confirm)
        KitDialog(
            title = confirm.title,
            onDismiss = { pending = null },
            confirm = KitAction(stringResource(R.string.extview_confirm_default)) { pending = null; run(action) },
            dismiss = KitAction(stringResource(R.string.extview_cancel)) { pending = null },
            tone = if (confirm.destructive) Tone.Danger else Tone.Neutral,
        ) {
            confirm.body?.let { BasicText(it, style = Kit.text.body.copy(color = Kit.colors.plainText)) }
        }
    }
}

/**
 * Draws one planned node. [bounded] says the parent gives this node a finite height (the root, and layouts down from it that do not
 * scroll): only then may a child take weight or a data component fill, because a weight under a scroll has no space to share.
 * [scroll] is set by the root and by a tab that holds no filling body: its column scrolls.
 */
@Composable
internal fun PlanNodeView(n: PlanNode, modifier: Modifier = Modifier, bounded: Boolean = false, scroll: Boolean = false) {
    when (n.type) {
        ViewType.COLUMN -> ColumnNode(n, modifier, bounded, scroll)
        ViewType.ROW -> RowNode(n, modifier, bounded)
        ViewType.SECTION -> SectionNode(n, modifier)
        ViewType.GROUP -> GroupNode(n, modifier)
        ViewType.TABS -> TabsNode(n, modifier, bounded)
        ViewType.TAB -> ColumnNode(n, modifier, bounded, scroll)
        ViewType.SPLIT -> SplitNode(n, modifier, bounded)
        ViewType.TEXT, ViewType.MARKDOWN, ViewType.KEY_VALUE, ViewType.TAG, ViewType.STATUS_DOT, ViewType.PROGRESS, ViewType.ICON,
        ViewType.IMAGE, ViewType.CODE, ViewType.BANNER, ViewType.EMPTY_STATE, ViewType.DIFF, ViewType.SPARKLINE -> ContentNode(n, modifier)
        ViewType.LIST, ViewType.TREE, ViewType.TABLE, ViewType.LOG_STREAM, ViewType.CHAT -> DataNode(n, modifier)
        ViewType.BUTTON, ViewType.ICON_BUTTON, ViewType.TOGGLE, ViewType.FIELD, ViewType.SELECT, ViewType.SLIDER, ViewType.SEARCH,
        ViewType.FORM, ViewType.COMPOSER -> InputNode(n, modifier, bounded)
        // Reserved: the plan never emits it, and a future one an older app cannot draw is skipped rather than failing the view.
        ViewType.TERMINAL -> Unit
    }
}
