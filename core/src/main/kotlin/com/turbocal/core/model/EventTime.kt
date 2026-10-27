package com.turbocal.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * When an event instance happens. The three kinds are kept apart on purpose
 * (AGENTS.md §4): an all-day event is a range of dates, a floating event is a
 * wall-clock time in whatever zone the phone is in, and only zoned/UTC events
 * are fixed instants. None of them is converted to another kind before display.
 */
sealed class EventTime {
    /** [endExclusive] follows iCalendar: a one-day event on the 5th ends on the 6th. */
    data class AllDay(val start: LocalDate, val endExclusive: LocalDate) : EventTime()

    data class Timed(val start: Instant, val end: Instant) : EventTime()

    data class Floating(val start: LocalDateTime, val end: LocalDateTime) : EventTime()

    fun startInstant(zone: ZoneId): Instant = when (this) {
        is AllDay -> start.atStartOfDay(zone).toInstant()
        is Timed -> start
        is Floating -> start.atZone(zone).toInstant()
    }

    fun endInstant(zone: ZoneId): Instant = when (this) {
        is AllDay -> endExclusive.atStartOfDay(zone).toInstant()
        is Timed -> end
        is Floating -> end.atZone(zone).toInstant()
    }

    /**
     * True when this overlaps [from, to). A zero-length event counts when it
     * starts inside the range.
     */
    fun overlaps(from: Instant, to: Instant, zone: ZoneId): Boolean {
        val s = startInstant(zone)
        val e = endInstant(zone)
        if (!s.isBefore(to)) return false
        return if (e == s) !s.isBefore(from) else e.isAfter(from)
    }

    /** True when this shows on [day] in [zone]. All-day events compare dates, never instants. */
    fun occursOn(day: LocalDate, zone: ZoneId): Boolean = when (this) {
        is AllDay -> !day.isBefore(start) && day.isBefore(endExclusive)
        else -> overlaps(day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant(), zone)
    }

    /** The first local date this shows on in [zone]. */
    fun firstDay(zone: ZoneId): LocalDate = when (this) {
        is AllDay -> start
        is Timed -> start.atZone(zone).toLocalDate()
        is Floating -> start.toLocalDate()
    }

    /** The last local date this shows on in [zone] (inclusive). */
    fun lastDay(zone: ZoneId): LocalDate = when (this) {
        is AllDay -> maxOf(start, endExclusive.minusDays(1))
        else -> {
            val s = startInstant(zone)
            val e = endInstant(zone)
            if (e == s) s.atZone(zone).toLocalDate() else e.minusNanos(1).atZone(zone).toLocalDate()
        }
    }

    val kind: Kind
        get() = when (this) {
            is AllDay -> Kind.ALL_DAY
            is Timed -> Kind.TIMED
            is Floating -> Kind.FLOATING
        }

    /**
     * Zone-independent sort/storage keys in epoch millis. A Timed key is the real
     * instant. AllDay and Floating keys read their wall time as if it were UTC, so
     * they are within ±14 h of the real instant in any zone. A database range query
     * widened by [KEY_SLACK_MILLIS] on both sides therefore never misses a row,
     * and [occursOn]/[overlaps] then give the exact answer. Rows never need
     * rewriting when the phone's zone changes.
     */
    val startKey: Long
        get() = when (this) {
            is AllDay -> start.toEpochDay() * DAY_MILLIS
            is Timed -> start.toEpochMilli()
            is Floating -> start.toInstant(ZoneOffset.UTC).toEpochMilli()
        }

    val endKey: Long
        get() = when (this) {
            is AllDay -> endExclusive.toEpochDay() * DAY_MILLIS
            is Timed -> end.toEpochMilli()
            is Floating -> end.toInstant(ZoneOffset.UTC).toEpochMilli()
        }

    enum class Kind(val code: Int) {
        ALL_DAY(0), TIMED(1), FLOATING(2);

        companion object {
            fun of(code: Int): Kind = values().first { it.code == code }
        }
    }

    companion object {
        const val DAY_MILLIS = 86_400_000L

        /** UTC offsets run from -12 h to +14 h. */
        const val KEY_SLACK_MILLIS = 15 * 3_600_000L

        fun fromKeys(kind: Kind, startKey: Long, endKey: Long): EventTime = when (kind) {
            Kind.ALL_DAY -> AllDay(
                LocalDate.ofEpochDay(Math.floorDiv(startKey, DAY_MILLIS)),
                LocalDate.ofEpochDay(Math.floorDiv(endKey, DAY_MILLIS)),
            )
            Kind.TIMED -> Timed(Instant.ofEpochMilli(startKey), Instant.ofEpochMilli(endKey))
            Kind.FLOATING -> Floating(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(startKey), ZoneOffset.UTC),
                LocalDateTime.ofInstant(Instant.ofEpochMilli(endKey), ZoneOffset.UTC),
            )
        }
    }
}
