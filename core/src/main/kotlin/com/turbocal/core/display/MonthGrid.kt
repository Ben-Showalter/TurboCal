package com.turbocal.core.display

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** One event as a month cell draws it: a full-width stripe in its calendar's colour. */
data class MonthEvent(val id: Long, val color: Int, val title: String)

/** [events] are in Day-view order (all-day first, then by start); the view decides how many fit. */
data class MonthCell(val date: LocalDate, val inMonth: Boolean, val isToday: Boolean, val events: List<MonthEvent>)

/** Six weeks starting on the locale's first weekday, so the grid never changes height. */
data class MonthGrid(val month: YearMonth, val firstDayOfWeek: DayOfWeek, val cells: List<MonthCell>) {
    val gridStart: LocalDate get() = cells.first().date
    val gridEndExclusive: LocalDate get() = cells.last().date.plusDays(1)

    companion object {
        const val WEEKS = 6

        fun range(month: YearMonth, firstDayOfWeek: DayOfWeek): Pair<LocalDate, LocalDate> {
            val first = month.atDay(1)
            val back = Math.floorMod(first.dayOfWeek.value - firstDayOfWeek.value, 7)
            val start = first.minusDays(back.toLong())
            return start to start.plusDays(WEEKS * 7L)
        }

        fun build(month: YearMonth, firstDayOfWeek: DayOfWeek, today: LocalDate, events: List<DisplayEvent>, zone: ZoneId): MonthGrid {
            val (start, end) = range(month, firstDayOfWeek)
            val byDay = HashMap<LocalDate, MutableList<DisplayEvent>>()
            for (e in events) {
                var d = maxOf(e.time.firstDay(zone), start)
                val last = minOf(e.time.lastDay(zone), end.minusDays(1))
                while (!d.isAfter(last)) {
                    if (e.time.occursOn(d, zone)) byDay.getOrPut(d) { mutableListOf() }.add(e)
                    d = d.plusDays(1)
                }
            }
            val cells = (0 until WEEKS * 7).map { i ->
                val d = start.plusDays(i.toLong())
                val list = byDay[d]?.let { sortForDay(it, zone) }.orEmpty()
                MonthCell(d, YearMonth.from(d) == month, d == today, list.map { MonthEvent(it.id, it.color, it.title) })
            }
            return MonthGrid(month, firstDayOfWeek, cells)
        }
    }
}

/** D-pad movement in the month grid. */
object MonthNav {
    /** Up/down (and any plain day/week offset): may leave the month. */
    fun move(selected: LocalDate, dx: Int, dy: Int): LocalDate = selected.plusDays(dx + 7L * dy)

    /**
     * LEFT/RIGHT (owner, round 4): move along the week row; past the row's end
     * (Saturday for a Sunday start) page to the next month, past its start to the
     * previous month, landing on the same weekday in the same row of that month's
     * grid. If that cell belongs to a neighbouring month (top or bottom rows), it
     * steps a week at a time back into the target month.
     */
    fun moveHorizontal(selected: LocalDate, dx: Int, firstDayOfWeek: DayOfWeek): LocalDate {
        val col = Math.floorMod(selected.dayOfWeek.value - firstDayOfWeek.value, 7)
        val target = col + dx
        if (target in 0..6) return selected.plusDays(dx.toLong())
        // The selection may sit on a greyed day of the neighbouring month; page from the
        // month the grid is showing, which is the selected date's own month.
        val shown = YearMonth.from(selected)
        val (start, _) = MonthGrid.range(shown, firstDayOfWeek)
        val row = ((selected.toEpochDay() - start.toEpochDay()) / 7).toInt()
        val next = if (dx > 0) shown.plusMonths(1) else shown.minusMonths(1)
        val (nextStart, _) = MonthGrid.range(next, firstDayOfWeek)
        var d = nextStart.plusDays(row * 7L + col)
        while (YearMonth.from(d) != next) d = d.plusWeeks(if (d.isBefore(next.atDay(1))) 1 else -1)
        return d
    }

    /** 1 / 3: previous / next month, keeping the day of month where it exists. */
    fun shiftMonth(selected: LocalDate, months: Int): LocalDate = selected.plusMonths(months.toLong())
}
