package com.turbocal.core.display

import com.turbocal.core.model.EventTime
import java.time.LocalDate
import java.time.ZoneId

/** One line in the Agenda or Day list. Headers are not focusable; events and "No events" are. */
sealed class Row {
    abstract val stableId: Long

    data class DayHeader(val date: LocalDate, val label: String, val isToday: Boolean) : Row() {
        override val stableId get() = -(date.toEpochDay() * 2 + 1)
    }

    data class Event(
        val eventId: Long,
        val date: LocalDate,
        val timeLabel: String,
        val title: String,
        val subtitle: String,
        val color: Int,
    ) : Row() {
        // An event shown on several days needs a distinct id per day.
        override val stableId get() = eventId * 4096 + Math.floorMod(date.toEpochDay(), 4096L)
    }

    data class Empty(val date: LocalDate, val label: String) : Row() {
        override val stableId get() = -(date.toEpochDay() * 2 + 2)
    }
}

private val NO_TITLE = "(No title)"

/** All-day first, then by start, then title — the order every view uses. */
internal fun sortForDay(events: List<DisplayEvent>, zone: ZoneId) = events.sortedWith(
    compareBy<DisplayEvent>({ if (it.time is EventTime.AllDay) 0 else 1 }, { it.time.startInstant(zone) }, { it.title.lowercase() }),
)

private fun eventRow(e: DisplayEvent, day: LocalDate, zone: ZoneId, fmt: Formats) = Row.Event(
    eventId = e.id,
    date = day,
    timeLabel = fmt.rowTime(e.time, day, zone),
    title = e.title.ifBlank { NO_TITLE },
    subtitle = e.location,
    color = e.color,
)

/**
 * Agenda: a header for each day that has events, plus today always (with a
 * "No events" row when empty), so the list can open on today. An event shows
 * on every day it covers.
 */
object AgendaBuilder {
    fun build(
        events: List<DisplayEvent>,
        from: LocalDate,
        toExclusive: LocalDate,
        today: LocalDate,
        zone: ZoneId,
        fmt: Formats,
        includeEmptyDays: Boolean = false,
    ): List<Row> {
        val byDay = java.util.TreeMap<LocalDate, MutableList<DisplayEvent>>()
        if (includeEmptyDays) {
            var d = from
            while (d.isBefore(toExclusive)) {
                byDay[d] = mutableListOf()
                d = d.plusDays(1)
            }
        }
        for (e in events) {
            var d = maxOf(e.time.firstDay(zone), from)
            val last = minOf(e.time.lastDay(zone), toExclusive.minusDays(1))
            while (!d.isAfter(last)) {
                if (e.time.occursOn(d, zone)) byDay.getOrPut(d) { mutableListOf() }.add(e)
                d = d.plusDays(1)
            }
        }
        if (!today.isBefore(from) && today.isBefore(toExclusive)) byDay.getOrPut(today) { mutableListOf() }

        val rows = ArrayList<Row>()
        for ((day, list) in byDay) {
            rows += Row.DayHeader(day, fmt.dayHeader(day, today), day == today)
            if (list.isEmpty()) rows += Row.Empty(day, "No events")
            for (e in sortForDay(list, zone)) rows += eventRow(e, day, zone, fmt)
        }
        return rows
    }

    /** Index of the row to focus first: today's first event, or its "No events" row. */
    fun initialIndex(rows: List<Row>, day: LocalDate): Int {
        val header = rows.indexOfFirst { it is Row.DayHeader && !it.date.isBefore(day) }
        if (header < 0) return (rows.size - 1).coerceAtLeast(0)
        return (header + 1).coerceAtMost(rows.size - 1)
    }

    /** First focusable row on the next/previous day that has rows; null at the ends. */
    fun dayJump(rows: List<Row>, fromIndex: Int, direction: Int): Int? {
        val current = rows.getOrNull(fromIndex)?.let { dayOf(it) } ?: return null
        val headers = rows.withIndex().filter { it.value is Row.DayHeader }
        val target = if (direction > 0) headers.firstOrNull { (it.value as Row.DayHeader).date.isAfter(current) }
        else headers.lastOrNull { (it.value as Row.DayHeader).date.isBefore(current) }
        return target?.let { (it.index + 1).coerceAtMost(rows.size - 1) }
    }

    fun dayOf(row: Row): LocalDate = when (row) {
        is Row.DayHeader -> row.date
        is Row.Event -> row.date
        is Row.Empty -> row.date
    }
}

/** Week view: all seven days, each with a header, empty days included. */
object WeekBuilder {
    fun weekStart(day: LocalDate, firstDayOfWeek: java.time.DayOfWeek): LocalDate =
        day.minusDays(Math.floorMod(day.dayOfWeek.value - firstDayOfWeek.value, 7).toLong())

    fun build(events: List<DisplayEvent>, weekStart: LocalDate, today: LocalDate, zone: ZoneId, fmt: Formats): List<Row> =
        AgendaBuilder.build(events, weekStart, weekStart.plusDays(7), today, zone, fmt, includeEmptyDays = true)
}

/** Day view: that day's events, all-day ones at the top. */
object DayBuilder {
    fun build(events: List<DisplayEvent>, day: LocalDate, zone: ZoneId, fmt: Formats): List<Row> {
        val list = sortForDay(events.filter { it.time.occursOn(day, zone) }, zone)
        if (list.isEmpty()) return listOf(Row.Empty(day, "No events"))
        return list.map { eventRow(it, day, zone, fmt) }
    }
}
