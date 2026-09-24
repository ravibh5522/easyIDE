package dev.easyide.app.ui.shell

/** What one Back press did, in the order shell-model.md section 11 tries them. */
enum class BackStep {
    /** 1. A dialog, sheet, menu, palette or IME is open: the host closes it; the state is unchanged. */
    CLOSE_TRANSIENT,

    /** 2. A panel that is showing as an overlay was closed. */
    CLOSE_PANEL,

    /** 3. The active group stepped back in its history ("Back to <previous document>" on a phone). */
    HISTORY_BACK,

    /** 3 (phone, app scope). A pushed document went back to its list. */
    POP_TO_LIST,

    /** 5. Compact: focus moved to the navigation surface, so the next Back leaves the scope. */
    FOCUS_NAV,

    /** 6. Leaving the workspace needs the unsaved-changes prompt (save all / discard / cancel); the state is unchanged. */
    CONFIRM_UNSAVED,

    /** 6. Left the workspace for app scope. */
    LEAVE_WORKSPACE,

    /** 6. Left a non-Home app destination for Home. */
    GO_HOME,

    /** Nothing left in the shell: the system handles Back. */
    SYSTEM,
}

/** What the host knows and the pure state does not: an open transient, and whether any document is unsaved. */
data class BackContext(val transientOpen: Boolean = false, val hasUnsaved: Boolean = false)

data class BackResult(val state: ShellState, val step: BackStep)

/**
 * The single Back rule for every size class (shell-model.md section 11). Step 4 of the spec says to
 * leave tabs alone, so it has no code: a Back never closes a document.
 */
object BackNavigation {

    fun back(state: ShellState, context: BackContext = BackContext()): BackResult {
        if (context.transientOpen) return BackResult(state, BackStep.CLOSE_TRANSIENT)
        closePanel(state)?.let { return BackResult(it, BackStep.CLOSE_PANEL) }
        stepBack(state)?.let { return BackResult(it, BackStep.HISTORY_BACK) }
        if (state.scope == ShellScope.APP && state.compact && state.pushed) {
            return BackResult(state.copy(pushed = false), BackStep.POP_TO_LIST)
        }
        if (state.compact && !state.navFocused) return BackResult(state.copy(navFocused = true), BackStep.FOCUS_NAV)
        return leaveScope(state, context)
    }

    private fun closePanel(state: ShellState): ShellState? {
        val showing = ShellLimits.overlays(state.window).filter { state.current.layout.isOpen(it) }.toSet()
        if (state.scope == ShellScope.APP && state.compact || showing.isEmpty()) return null
        return state.withCurrent(state.current.copy(layout = state.current.layout.dismiss(showing, state.rule)))
    }

    private fun stepBack(state: ShellState): ShellState? {
        val stage = state.current.stage.back() ?: return null
        return state.withCurrent(state.current.copy(stage = stage))
    }

    private fun leaveScope(state: ShellState, context: BackContext): BackResult = when {
        state.scope == ShellScope.WORKSPACE && context.hasUnsaved -> BackResult(state, BackStep.CONFIRM_UNSAVED)
        state.scope == ShellScope.WORKSPACE -> BackResult(state.copy(workspace = null, pushed = false, navFocused = false), BackStep.LEAVE_WORKSPACE)
        state.app.nav != null && state.app.nav != CoreShell.HOME -> BackResult(goHome(state), BackStep.GO_HOME)
        else -> BackResult(state, BackStep.SYSTEM)
    }

    /** Home is a fixed core destination, so its container is known here and no host lookup is needed. */
    private fun goHome(state: ShellState): ShellState {
        val layout = state.app.layout.show(Placement.SIDEBAR, state.rule, CoreShell.HOME_PROJECTS)
        return state.copy(app = state.app.copy(nav = CoreShell.HOME, layout = layout), pushed = false, navFocused = false)
    }
}
