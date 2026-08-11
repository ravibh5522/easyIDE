package dev.easyide.app.ui.screens.workspace

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * The client `TerminalView` needs for input routing, modifier keys and
 * logging. Every `onKeyDown`/`onCodePoint` hook returns `false` ("I did not
 * handle this specially") so the view's own, already-correct default
 * handling runs - translating arrow keys, Ctrl combinations and Unicode
 * input into the right bytes for the pty itself. There is no soft-keyboard
 * modifier state (a "sticky Ctrl" toggle) here: [TerminalKeyRow] sends raw
 * control bytes directly for the one case that matters on a soft keyboard
 * (`^C`), which needs no modifier tracking at all.
 */
class EasyTerminalViewClient : TerminalViewClient {

    /**
     * Set by the composable that creates the view. Needed only to raise the
     * soft keyboard - see [onSingleTapUp].
     */
    var terminalView: TerminalView? = null

    override fun onScale(scale: Float): Float = 1f

    /**
     * Raises the soft keyboard on tap.
     *
     * `TerminalView.onSingleTapUp` calls `requestFocus()` and then delegates
     * here, but it never touches the IME itself - showing the keyboard is
     * deliberately left to the client, because a terminal embedded with a
     * hardware keyboard should not force one up. This override was empty, so
     * tapping the terminal focused it and nothing appeared: the pane was
     * unusable by touch.
     */
    override fun onSingleTapUp(e: MotionEvent) {
        val view = terminalView ?: return
        val imm = view.context.getSystemService(InputMethodManager::class.java) ?: return
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {}

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "exception", e) }
}
