package dev.easyide.app.ui.screens.home

enum class AgeUnit { NOW, MINUTES, HOURS, DAYS, WEEKS, MONTHS, YEARS }

/**
 * A coarse "how long ago": one unit and a whole count. Kept as data rather than
 * text so the wording (and its plurals) stays in string resources.
 */
data class RelativeAge(val unit: AgeUnit, val amount: Int)

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
private const val DAYS_PER_WEEK = 7L
private const val DAYS_PER_MONTH = 30L
private const val DAYS_PER_YEAR = 365L

/**
 * Buckets the gap between [thenMs] and [nowMs]. Anything under a minute - and a
 * timestamp in the future, from clock adjustment - reads as "just now", so a
 * card never shows a negative or a flickering "0 minutes ago".
 */
fun relativeAge(nowMs: Long, thenMs: Long): RelativeAge {
    val minutes = (nowMs - thenMs) / MS_PER_MINUTE
    val hours = minutes / MINUTES_PER_HOUR
    val days = hours / HOURS_PER_DAY
    return when {
        minutes < 1 -> RelativeAge(AgeUnit.NOW, 0)
        hours < 1 -> RelativeAge(AgeUnit.MINUTES, minutes.toInt())
        days < 1 -> RelativeAge(AgeUnit.HOURS, hours.toInt())
        days < DAYS_PER_WEEK -> RelativeAge(AgeUnit.DAYS, days.toInt())
        days < DAYS_PER_MONTH -> RelativeAge(AgeUnit.WEEKS, (days / DAYS_PER_WEEK).toInt())
        days < DAYS_PER_YEAR -> RelativeAge(AgeUnit.MONTHS, (days / DAYS_PER_MONTH).toInt())
        else -> RelativeAge(AgeUnit.YEARS, (days / DAYS_PER_YEAR).toInt())
    }
}
