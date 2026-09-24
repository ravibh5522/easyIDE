package dev.easyide.sandbox.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.os.Build

/** What the foreground service can ask of whoever owns the running sessions. */
fun interface SessionHost {
    /** Ends every running session, saving unsaved work first (the notification's "Stop all"). */
    fun endAllSessions()
}

/**
 * The link between the app's live sessions and [SandboxForegroundService].
 *
 * A pty subprocess is a child of the app process, so it survives backgrounding exactly as
 * long as Android lets the process live. A foreground service is what asks for that:
 * it raises the process out of the cached tier where the system reclaims apps within
 * seconds. So the service runs while, and only while, at least one session is live; the
 * owner reports the count here. It does not survive the process being killed anyway (a
 * user swipe, the OOM killer under pressure): that is why the app also persists sessions
 * to disk (docs/decision/0023-workspace-session-lifetime.md).
 */
object SandboxKeepAlive {

    @Volatile
    internal var host: SessionHost? = null
        private set

    @Volatile
    internal var sessions: Int = 0
        private set

    /**
     * Reports how many sessions are live. Starts (or refreshes the notification of) the
     * service for a positive count and stops it at zero. Call from the main thread.
     */
    fun update(context: Context, count: Int, owner: SessionHost) {
        host = owner
        sessions = count
        if (count > 0) start(context.applicationContext) else SandboxForegroundService.stop(context.applicationContext)
    }

    /**
     * Android refuses to start a foreground service from the background (API 31+). Sessions
     * come into being from a screen the user is looking at, so this only fails when the
     * process was resurrected without a UI; then there is nothing to keep alive either.
     */
    private fun start(context: Context) {
        try {
            SandboxForegroundService.start(context)
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || e !is ForegroundServiceStartNotAllowedException) throw e
        }
    }
}
