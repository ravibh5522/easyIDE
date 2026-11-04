package dev.easyide.sandbox.bootstrap

/**
 * Splits a setup command's output into progress and log: status lines become a step fraction (and
 * their message, a readable log line), everything else passes through unchanged. Fractions are only
 * passed on when they moved by at least [MIN_STEP], so a chatty dpkg does not flood the UI.
 */
internal class SetupReporter(
    private val onLog: ProgressReporter,
    private val onFraction: (Float) -> Unit,
) {
    private var last = -1f

    fun line(line: String) {
        val update = SetupProgress.parse(line) ?: return onLog(line)
        update.message?.let(onLog)
        if (update.fraction - last >= MIN_STEP || update.fraction >= 1f && last < 1f) {
            last = update.fraction
            onFraction(update.fraction)
        }
    }

    private companion object {
        const val MIN_STEP = 0.005f
    }
}
