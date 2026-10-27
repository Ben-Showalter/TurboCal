package com.turbocal.core.sync

/**
 * Manual "sync now" is throttled (guide §6, AGENTS.md §5): within
 * [minIntervalMillis] of the last full success it answers with
 * "Already up to date - try again in about N min" instead of fetching.
 */
class ManualSyncThrottle(private val minIntervalMillis: Long = 2 * 60_000L) {

    /** Whole minutes to wait (at least 1), or null when a sync may run now. */
    fun minutesToWait(lastSuccessAt: Long?, now: Long): Int? {
        if (lastSuccessAt == null) return null
        val left = lastSuccessAt + minIntervalMillis - now
        if (left <= 0 || lastSuccessAt > now) return null
        return ((left + 59_999) / 60_000).toInt()
    }

    fun message(minutes: Int): String =
        "Already up to date - try again in about $minutes min"
}
