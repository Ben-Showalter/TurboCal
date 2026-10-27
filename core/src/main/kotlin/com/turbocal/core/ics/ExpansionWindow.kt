package com.turbocal.core.ics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The rolling range recurrences are expanded into at sync time (AGENTS.md §4).
 * [from] is inclusive, [toExclusive] exclusive, both local dates in [zone].
 */
data class ExpansionWindow(val from: LocalDate, val toExclusive: LocalDate, val zone: ZoneId) {
    val fromInstant: Instant get() = from.atStartOfDay(zone).toInstant()
    val toInstant: Instant get() = toExclusive.atStartOfDay(zone).toInstant()

    companion object {
        const val DAYS_BACK = 60L
        const val DAYS_FORWARD = 365L

        fun around(today: LocalDate, zone: ZoneId): ExpansionWindow =
            ExpansionWindow(today.minusDays(DAYS_BACK), today.plusDays(DAYS_FORWARD + 1), zone)
    }
}
