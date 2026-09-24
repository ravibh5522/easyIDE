package dev.easyide.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import dev.easyide.app.R
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitIconButton
import dev.easyide.app.ui.kit.KitProgress
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection

/**
 * What is going on right now: shells, language servers, installs. Hidden when there is nothing,
 * so an idle Home does not carry an empty box. A row's tap attaches (opens the project, or the
 * install screen); stop is shown only when the owner wired it.
 *
 * @param title the section header: "Running" on Home, "Sessions" on a project page.
 * @param onStop null when nothing can stop; installs are never stoppable from here.
 */
@Composable
internal fun RunningSection(
    title: String,
    items: List<RunningItem>,
    onAttach: (RunningItem) -> Unit,
    onStop: ((RunningItem) -> Unit)?,
) {
    if (items.isEmpty()) return
    KitSection(title) {
        items.forEach { item -> RunningRow(item, onAttach, onStop.takeIf { item.kind != RunningKind.INSTALL }) }
    }
}

@Composable
private fun RunningRow(item: RunningItem, onAttach: (RunningItem) -> Unit, onStop: ((RunningItem) -> Unit)?) {
    KitRow(
        title = item.name,
        subtitle = joinParts(item.phase.label(), item.subject, item.environment),
        mono = true,
        leading = { StateDot(item.phase.tone()) },
        onClick = { onAttach(item) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
                item.rssKb?.let { MonoText(processSize(it).text()) }
                if (item.kind == RunningKind.INSTALL) KitProgress(null)
                if (onStop != null) {
                    KitIconButton(Icons.Filled.Close, stringResource(R.string.home_running_stop, item.name), { onStop(item) })
                }
            }
        },
    )
}
