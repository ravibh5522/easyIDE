package dev.easyide.app.ui.screens.workspace.session

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.session.ExternalState
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitAction
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitDialog
import dev.easyide.app.ui.screens.workspace.EditorTab
import dev.easyide.app.ui.screens.workspace.NoticeBar

/**
 * Asks what to do about the first open tab whose file changed on disk while it holds unsaved
 * edits. "Decide later" hides that particular disk version for this screen; the tab stays
 * flagged and saving stays blocked, and a further outside change asks again.
 */
@Composable
fun ExternalChangeDialog(tabs: List<EditorTab>, onResolve: (path: String, keepMine: Boolean) -> Unit) {
    var deferred by remember { mutableStateOf(emptySet<Pair<String, Int>>()) }
    val pending = tabs.firstNotNullOfOrNull { tab ->
        val conflict = tab.externalState as? ExternalState.Conflict
        if (conflict != null && (tab.relativePath to conflict.diskText.hashCode()) !in deferred) tab to conflict else null
    } ?: return
    val (tab, conflict) = pending
    val path = tab.relativePath
    val later = { deferred = deferred + (path to conflict.diskText.hashCode()) }

    KitDialog(
        title = stringResource(R.string.session_conflict_title, tab.name),
        onDismiss = later,
        confirm = KitAction(stringResource(R.string.session_conflict_keep_mine)) { onResolve(path, true) },
        dismiss = KitAction(stringResource(R.string.session_conflict_later), later),
    ) {
        BasicText(stringResource(R.string.session_conflict_body), style = Kit.type.bodyMedium.copy(color = Kit.colors.plainText))
        KitButton(
            stringResource(R.string.session_conflict_use_disk),
            { onResolve(path, false) },
            Modifier.padding(top = Kit.space.s),
            KitButtonStyle.Secondary,
        )
    }
}

/** Tells the user why a tab's save is blocked or what saving will do; nothing when the tab matches the disk. */
@Composable
fun ExternalStateNotice(state: ExternalState) {
    when (state) {
        ExternalState.InSync -> Unit
        is ExternalState.Conflict -> NoticeBar(stringResource(R.string.session_notice_conflict))
        ExternalState.Gone -> NoticeBar(stringResource(R.string.session_notice_gone))
    }
}
