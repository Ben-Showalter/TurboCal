package com.turbocal.core.display

import com.turbocal.core.model.EventTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every date and time string the screens show. Formatting lives here, not in
 * Activities, so it is unit-tested (AGENTS.md §8).
 */
class Formats(locale: Locale = Locale.getDefault(), use24h: Boolean = false) {
    private val time = DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", locale)
    private val dayShort = DateTimeFormatter.ofPattern("EEE, MMM d", locale)
    private val dayYear = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", locale)
    private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
    private val dateShort = DateTimeFormatter.ofPattern("MMM d", locale)
    private val weekdayLetter = DateTimeFormatter.ofPattern("EEEEE", locale)

    fun time(t: LocalTime): String = time.format(t)

    fun time(i: Instant, zone: ZoneId): String = time(i.atZone(zone).toLocalTime())

    /** "Today · Tue, Oct 27", "Tomorrow · …", "Thu, Oct 29", with the year only when it isn't this year's. */
    fun dayHeader(day: LocalDate, today: LocalDate): String {
        val base = if (day.year == today.year) dayShort.format(day) else dayYear.format(day)
        return when (day) {
            today -> "Today · $base"
            today.plusDays(1) -> "Tomorrow · $base"
            today.minusDays(1) -> "Yesterday · $base"
            else -> base
        }
    }

    fun dayTitle(day: LocalDate, today: LocalDate): String =
        if (day.year == today.year) dayShort.format(day) else dayYear.format(day)

    fun monthTitle(day: LocalDate): String = monthYear.format(day)

    fun weekdayLetter(day: LocalDate): String = weekdayLetter.format(day)

    /**
     * The short label in a list row for [t] on [day]: "All day", "9:30 AM",
     * "until 3:00 AM" (continued from an earlier day) or "All day" again for
     * a timed event that covers the whole day.
     */
    fun rowTime(t: EventTime, day: LocalDate, zone: ZoneId): String {
        if (t is EventTime.AllDay) return ALL_DAY
        val start = t.startInstant(zone)
        val end = t.endInstant(zone)
        val dayStart = day.atStartOfDay(zone).toInstant()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
        return when {
            !start.isBefore(dayStart) -> time(start, zone)
            end.isAfter(dayEnd) -> ALL_DAY
            else -> "until ${time(end, zone)}"
        }
    }

    /** The detail screen's "when" lines. */
    fun detailWhen(t: EventTime, zone: ZoneId, today: LocalDate): String = when (t) {
        is EventTime.AllDay -> {
            val last = t.endExclusive.minusDays(1)
            if (!last.isAfter(t.start)) "${dayTitle(t.start, today)}\nAll day"
            else "${dayTitle(t.start, today)} – ${dayTitle(last, today)}\nAll day"
        }
        else -> {
            val s = t.startInstant(zone).atZone(zone).toLocalDateTime()
            val e = t.endInstant(zone).atZone(zone).toLocalDateTime()
            span(s, e, today)
        }
    }

    private fun span(s: LocalDateTime, e: LocalDateTime, today: LocalDate): String = when {
        s == e -> "${dayTitle(s.toLocalDate(), today)}\n${time(s.toLocalTime())}"
        s.toLocalDate() == e.toLocalDate() ->
            "${dayTitle(s.toLocalDate(), today)}\n${time(s.toLocalTime())} – ${time(e.toLocalTime())}"
        else -> "${dayTitle(s.toLocalDate(), today)} ${time(s.toLocalTime())} –\n" +
            "${dayTitle(e.toLocalDate(), today)} ${time(e.toLocalTime())}"
    }

    /** "Synced 10:42 AM", "Synced Oct 26", "Never synced". */
    fun synced(at: Long?, now: Long, zone: ZoneId): String {
        if (at == null) return "Never synced"
        val then = Instant.ofEpochMilli(at).atZone(zone)
        val nowDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (then.toLocalDate()) {
            nowDay -> "Synced ${time(then.toLocalTime())}"
            nowDay.minusDays(1) -> "Synced yesterday ${time(then.toLocalTime())}"
            else -> "Synced ${dateShort.format(then)}"
        }
    }

    /** One status line per feed (AGENTS.md §1 rule 4): the error wins, with the last good sync after it. */
    fun feedStatus(enabled: Boolean, lastSuccessAt: Long?, lastError: String?, now: Long, zone: ZoneId): String {
        if (!enabled) return "Off · " + synced(lastSuccessAt, now, zone)
        if (lastError.isNullOrBlank()) return synced(lastSuccessAt, now, zone)
        val good = if (lastSuccessAt == null) "no data yet" else "last good " + synced(lastSuccessAt, now, zone).removePrefix("Synced ")
        return "Error: $lastError · $good"
    }

    companion object {
        const val ALL_DAY = "All day"
    }
}
