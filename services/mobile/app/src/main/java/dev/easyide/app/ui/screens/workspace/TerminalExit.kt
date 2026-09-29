package dev.easyide.app.ui.screens.workspace

/**
 * Why a terminal's process ended. The pty layer reports a signal as the negative signal number
 * (`-WTERMSIG`), and SIGKILL nobody in the app sent is Android's phantom-process killer or the
 * low-memory killer - the app's own kills (tab close, workspace end, command timeout) are
 * excluded by the caller.
 */
internal object TerminalExit {
    private const val SIGKILL_STATUS = -9

    fun killedBySystem(exitStatus: Int, killedByApp: Boolean): Boolean =
        exitStatus == SIGKILL_STATUS && !killedByApp
}
