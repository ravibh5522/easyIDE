package dev.easyide.app.ui.props

/**
 * The timing of the cursor blink (identity.md 7) as pure functions of clock readings, so it is
 * unit-tested without a frame clock. A blink is on for [Motion.BLINK_HALF_MS], then off for as
 * long, with no fade. Two rules shape it:
 *
 * - any input holds the cursor solid for [Motion.BLINK_RESET_MS] and the blink then starts again
 *   from the on phase;
 * - a surface with a cycle cap (Home and empty-state headers: [Motion.HEADER_BLINK_CYCLES]) blinks
 *   that many periods from [startMs] and is solid, with no timer left running, after that.
 *
 * Whether the blink runs at all (reduce motion, screen reader, the property) is decided by the
 * caller through [Motion.cursorBlinks]; these functions only answer "when".
 */
object BlinkClock {
    const val PERIOD_MS = 2L * Motion.BLINK_HALF_MS

    /** True while the block is drawn at [nowMs]. */
    fun visible(nowMs: Long, startMs: Long, lastInputMs: Long?, maxCycles: Int?): Boolean {
        if (capped(nowMs, startMs, maxCycles)) return true
        val resume = resumeAt(startMs, lastInputMs)
        return nowMs < resume || (nowMs - resume) % PERIOD_MS < Motion.BLINK_HALF_MS
    }

    /** Milliseconds from [nowMs] to the next change of [visible]; null when it stays solid from now on. */
    fun msUntilChange(nowMs: Long, startMs: Long, lastInputMs: Long?, maxCycles: Int?): Long? {
        if (capped(nowMs, startMs, maxCycles)) return null
        val resume = resumeAt(startMs, lastInputMs)
        val next = if (nowMs < resume) {
            resume - nowMs
        } else {
            val phase = (nowMs - resume) % PERIOD_MS
            if (phase < Motion.BLINK_HALF_MS) Motion.BLINK_HALF_MS - phase else PERIOD_MS - phase
        }
        return if (maxCycles == null) next else minOf(next, startMs + maxCycles * PERIOD_MS - nowMs)
    }

    private fun capped(nowMs: Long, startMs: Long, maxCycles: Int?): Boolean =
        maxCycles != null && nowMs >= startMs + maxCycles * PERIOD_MS

    /** The first instant the blink may run: the start, or the end of the solid hold after the last input. */
    private fun resumeAt(startMs: Long, lastInputMs: Long?): Long =
        if (lastInputMs == null) startMs else maxOf(startMs, lastInputMs + Motion.BLINK_RESET_MS)
}
