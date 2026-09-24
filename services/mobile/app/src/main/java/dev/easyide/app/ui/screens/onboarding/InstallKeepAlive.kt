package dev.easyide.app.ui.screens.onboarding

/**
 * Keeps the process alive while an install runs. Android reaps a backgrounded
 * app within seconds, and a several-minute download plus unpack would die with
 * it; the foreground service (whose notification is why onboarding asks for the
 * notification permission) is what prevents that.
 */
interface InstallKeepAlive {
    fun start()
    fun stop()
}
