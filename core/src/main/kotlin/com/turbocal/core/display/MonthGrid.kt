package com.turbocal.core.display

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class MonthCell(val date: LocalDate, val inMonth: Boolean, val isToday: Boolean, val colors: List<Int>)

/** Six weeks starting on the locale's first weekday, so the grid never changes height. */
data class MonthGrid(val month: YearMonth, val firstDayOfWeek: DayOfWeek, val cells: List<MonthCell>) {
    val gridStart: LocalDate get() = cells.first().date
    val gridEndExclusive: LocalDate get() = cells.last().date.plusDays(1)

    companion object {
        const val WEEKS = 6
        const val MAX_DOTS = 3

        fun range(month: YearMonth, firstDayOfWeek: DayOfWeek): Pair<LocalDate, LocalDate> {
            val first = month.atDay(1)
            val back = Math.floorMod(first.dayOfWeek.value - firstDayOfWeek.value, 7)
            val start = first.minusDays(back.toLong())
            return start to start.plusDays(WEEKS * 7L)
        }

        fun build(month: YearMonth, firstDayOfWeek: DayOfWeek, today: LocalDate, events: List<DisplayEvent>, zone: ZoneId): MonthGrid {
            val (start, end) = range(month, firstDayOfWeek)
            val colorsByDay = HashMap<LocalDate, LinkedHashSet<Int>>()
            for (e in events) {
                var d = maxOf(e.time.firstDay(zone), start)
                val last = minOf(e.time.lastDay(zone), end.minusDays(1))
                while (!d.isAfter(last)) {
                    if (e.time.occursOn(d, zone)) colorsByDay.getOrPut(d) { LinkedHashSet() }.add(e.color)
                    d = d.plusDays(1)
                }
            }
            val cells = (0 until WEEKS * 7).map { i ->
                val d = start.plusDays(i.toLong())
                MonthCell(d, YearMonth.from(d) == month, d == today, colorsByDay[d]?.take(MAX_DOTS).orEmpty())
            }
            return MonthGrid(month, firstDayOfWeek, cells)
        }
    }
}

/** D-pad movement in the month grid: the selection moves by day or week and may leave the month. */
object MonthNav {
    fun move(selected: LocalDate, dx: Int, dy: Int): LocalDate = selected.plusDays(dx + 7L * dy)

    /** 1 / 3: previous / next month, keeping the day of month where it exists. */
    fun shiftMonth(selected: LocalDate, months: Int): LocalDate = selected.plusMonths(months.toLong())
}
