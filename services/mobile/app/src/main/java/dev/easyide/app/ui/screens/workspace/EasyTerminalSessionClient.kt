package dev.easyide.app.ui.screens.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Bridges `TerminalSession`'s callbacks to the workspace. Kept a thin adapter
 * rather than folding into the ViewModel directly: these callbacks fire from
 * `TerminalSession`'s own background threads, hopped to the main thread by
 * its `MainThreadHandler` before reaching here, so by the time any of these
 * run touching Compose/ViewModel state is already safe - but the interface
 * itself has nothing to do with the workspace's own concerns (files,
 * environments), so it stays a separate small class.
 */
class EasyTerminalSessionClient(
    private val context: Context,
    private val onTitleChanged: (TerminalSession) -> Unit,
    private val onSessionFinished: (TerminalSession) -> Unit,
) : TerminalSessionClient {

    /**
     * Set by whichever `TerminalView` currently has this session attached
     * (see `TerminalPane.EasyTerminalView`), cleared when it does not. Unlike
     * the rest of this class's callbacks, `TerminalView` does *not* redraw
     * itself when the session's screen buffer changes - it has to be told to,
     * via `onScreenUpdated()`/`invalidate()`, and this is the hook that does
     * it. Nullable and reassigned rather than fixed at construction because
     * which view (if any) is showing this session can change - switching
     * tabs away and back reattaches the same session to the same View.
     */
    var onScreenChanged: (() -> Unit)? = null

    override fun onTextChanged(changedSession: TerminalSession) = onScreenChanged?.invoke() ?: Unit

    override fun onTitleChanged(changedSession: TerminalSession) = onTitleChanged.invoke(changedSession)

    override fun onSessionFinished(finishedSession: TerminalSession) = onSessionFinished.invoke(finishedSession)

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val target = session ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text ?: return
        target.write(text.toString())
    }

    // Deliberately silent: some programs (bash tab-completion ambiguity, for
    // one) print BEL often enough that a toast or sound per occurrence would
    // be more annoying than useful.
    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {
        // No dynamic recolouring beyond the theme baked into buildHtml-style
        // defaults yet; nothing to react to.
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        // Cursor blink state; TerminalView tracks and redraws this itself.
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        // Not surfaced in the UI - nothing needs the pid directly.
    }

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "exception", e) }
}
