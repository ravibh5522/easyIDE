package dev.easyide.app.ui.shell.host

import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.EditorGroup
import dev.easyide.app.ui.shell.TabState

/** What a tab's menu can do to its document. */
enum class TabAction { OPEN_BESIDE, MOVE_NEXT, KEEP, PIN, UNPIN, CLOSE, CLOSE_OTHERS, CLOSE_RIGHT, CLOSE_ALL }

/** The window facts a tab's menu depends on: whether the document may split off, and whether a group after this one exists. */
class TabMenuContext(val canBeside: Boolean, val canMoveNext: Boolean)

/**
 * The entries of one tab's menu in sections (a divider between sections), decided from the group alone so
 * both the strip's menu and a phone's switcher menu read the same rules. An entry that would do nothing is
 * left out rather than shown inert: "close others" with no other tab is not on offer.
 */
object TabMenuPlan {
    fun of(group: EditorGroup, key: DocumentUri, context: TabMenuContext): List<List<TabAction>> {
        val tab = group.tab(key) ?: return emptyList()
        val at = group.tabs.indexOfFirst { it.key == key }
        val stage = listOfNotNull(
            TabAction.OPEN_BESIDE.takeIf { context.canBeside },
            TabAction.MOVE_NEXT.takeIf { context.canMoveNext },
        )
        val state = when (tab.state) {
            TabState.PREVIEW -> listOf(TabAction.KEEP, TabAction.PIN)
            TabState.KEPT -> listOf(TabAction.PIN)
            TabState.PINNED -> listOf(TabAction.UNPIN)
        }
        val closing = listOfNotNull(
            TabAction.CLOSE,
            TabAction.CLOSE_OTHERS.takeIf { group.tabs.size > 1 },
            TabAction.CLOSE_RIGHT.takeIf { at < group.tabs.lastIndex },
            TabAction.CLOSE_ALL,
        )
        return listOf(stage, state, closing).filter { it.isNotEmpty() }
    }
}
