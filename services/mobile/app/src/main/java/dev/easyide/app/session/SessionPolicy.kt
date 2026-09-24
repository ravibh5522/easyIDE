package dev.easyide.app.session

/** Timing of hot-exit persistence, in one place. */
object SessionPolicy {
    /**
     * After a change, how long before the session is written. A timer, not a debounce: typing
     * without a pause still gets a backup every this often, which is the point of one.
     */
    const val SAVE_DELAY_MS = 2_000L

    /**
     * How long the app leaving the foreground may block on writing every workspace's session.
     * The writes are a few small files; the budget only bounds a pathological disk.
     */
    const val FLUSH_BUDGET_MS = 1_500L
}
