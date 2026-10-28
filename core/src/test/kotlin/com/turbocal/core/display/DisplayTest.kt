package com.turbocal.core.display

import com.turbocal.core.model.EventTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

class DisplayTest {
    private val zone = ZoneId.of("America/New_York")
    private val fmt = Formats(Locale.US, use24h = false)
    private val today = LocalDate.of(2026, 10, 27)

    private fun ev(id: Long, title: String, time: EventTime, color: Int = 1) =
        DisplayEvent(id, 1, "Cal", color, title, "", "", time)

    private fun at(s: String, e: String) = EventTime.Timed(Instant.parse(s), Instant.parse(e))

    private val events = listOf(
        ev(1, "Dentist", at("2026-10-28T13:30:00Z", "2026-10-28T14:30:00Z")),
        ev(2, "Holiday", EventTime.AllDay(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 30))),
        ev(3, "Late show", at("2026-10-29T02:00:00Z", "2026-10-29T06:00:00Z")), // 22:00 Oct 28 → 02:00 Oct 29
        ev(4, "", at("2026-10-26T12:00:00Z", "2026-10-26T13:00:00Z")),
    )

    @Test fun `agenda groups by day, keeps today, puts all-day first, repeats multi-day`() {
        val rows = AgendaBuilder.build(events, today.minusDays(60), today.plusDays(366), today, zone, fmt)
        val text = rows.map {
            when (it) {
                is Row.DayHeader -> "# ${it.label}"
                is Row.Event -> "${it.timeLabel} ${it.title}"
                is Row.Empty -> "- ${it.label}"
            }
        }
        assertEquals(
            listOf(
                "# Yesterday · Mon, Oct 26", "8:00 AM (No title)",
                "# Today · Tue, Oct 27", "- No events",
                "# Tomorrow · Wed, Oct 28", "All day Holiday", "9:30 AM Dentist", "10:00 PM Late show",
                "# Thu, Oct 29", "All day Holiday", "until 2:00 AM Late show",
            ),
            text,
        )
        assertEquals(3, AgendaBuilder.initialIndex(rows, today)) // today's "No events"
        assertEquals(5, AgendaBuilder.dayJump(rows, 3, +1))
        assertEquals(1, AgendaBuilder.dayJump(rows, 3, -1))
        assertEquals(null, AgendaBuilder.dayJump(rows, 1, -1))
        assertEquals(rows.size, rows.map { it.stableId }.toSet().size)
    }

    @Test fun `day view lists all-day first, empty day gets a row`() {
        val rows = DayBuilder.build(events, LocalDate.of(2026, 10, 28), zone, fmt)
        assertEquals(listOf("Holiday", "Dentist", "Late show"), rows.map { (it as Row.Event).title })
        assertTrue(DayBuilder.build(events, LocalDate.of(2026, 11, 3), zone, fmt).single() is Row.Empty)
    }

    @Test fun `month grid is six weeks from the first weekday and lists every event per cell`() {
        val g = MonthGrid.build(YearMonth.of(2026, 10), DayOfWeek.SUNDAY, today, events, zone)
        assertEquals(42, g.cells.size)
        assertEquals(LocalDate.of(2026, 9, 27), g.gridStart)
        assertEquals(DayOfWeek.SUNDAY, g.gridStart.dayOfWeek)
        // Day-view order: all-day first, then by time; the multi-day holiday is in both cells.
        val oct28 = g.cells.first { it.date == LocalDate.of(2026, 10, 28) }
        assertEquals(listOf("Holiday", "Dentist", "Late show"), oct28.events.map { it.title })
        val oct29 = g.cells.first { it.date == LocalDate.of(2026, 10, 29) }
        assertEquals(listOf("Holiday", "Late show"), oct29.events.map { it.title })
        assertTrue(g.cells.first { it.date == today }.isToday)
        assertTrue(g.cells.first { it.date == LocalDate.of(2026, 10, 30) }.events.isEmpty()) // DTEND exclusive
        val monday = MonthGrid.range(YearMonth.of(2026, 11), DayOfWeek.MONDAY).first
        assertEquals(LocalDate.of(2026, 10, 26), monday)
    }

    @Test fun `week shows all seven days with headers, empty days included`() {
        val start = WeekBuilder.weekStart(today, DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 10, 25), start)
        assertEquals(LocalDate.of(2026, 10, 26), WeekBuilder.weekStart(today, DayOfWeek.MONDAY))
        val rows = WeekBuilder.build(events, start, today, zone, fmt)
        val headers = rows.filterIsInstance<Row.DayHeader>().map { it.date }
        assertEquals((25..31).map { LocalDate.of(2026, 10, it) }, headers)
        // Sunday 25th has nothing; the holiday shows on the 28th and 29th.
        assertTrue(rows[1] is Row.Empty)
        assertEquals(2, rows.filterIsInstance<Row.Event>().count { it.title == "Holiday" })
        assertEquals("Oct 25 – 31", fmt.weekTitle(start, today))
        assertEquals("Oct 29 – Nov 4", fmt.weekTitle(LocalDate.of(2026, 10, 29), today))
        assertEquals("Dec 27 – Jan 2, 2027", fmt.weekTitle(LocalDate.of(2026, 12, 27), today))
    }

    @Test fun `left and right page months at the row ends, same weekday and row`() {
        val sun = DayOfWeek.SUNDAY
        // Inside a row: one day.
        assertEquals(LocalDate.of(2026, 10, 28), MonthNav.moveHorizontal(today, 1, sun))
        assertEquals(LocalDate.of(2026, 10, 26), MonthNav.moveHorizontal(today, -1, sun))
        // Sat Oct 31 is row 4 of October's grid; row 4's Saturday in November's grid is Nov 28.
        assertEquals(LocalDate.of(2026, 11, 28), MonthNav.moveHorizontal(LocalDate.of(2026, 10, 31), 1, sun))
        // Sun Oct 4 is row 1; row 1's Sunday in September's grid (starts Aug 30) is Sep 6.
        assertEquals(LocalDate.of(2026, 9, 6), MonthNav.moveHorizontal(LocalDate.of(2026, 10, 4), -1, sun))
        // Sat Oct 3 is row 0; November's grid row 0 Saturday is Nov 7 (Nov 1 is a Sunday).
        assertEquals(LocalDate.of(2026, 11, 7), MonthNav.moveHorizontal(LocalDate.of(2026, 10, 3), 1, sun))
        // Sat Dec 26 is row 3 of December (grid starts Nov 29) → January's grid (starts Dec 27) row 3 Saturday: Jan 23.
        assertEquals(LocalDate.of(2027, 1, 23), MonthNav.moveHorizontal(LocalDate.of(2026, 12, 26), 1, sun))
        // Bottom row, LEFT: Sun Jan 31 2027 is row 5; December's row 5 Sunday is Jan 3, so step back into December: Dec 27.
        assertEquals(LocalDate.of(2026, 12, 27), MonthNav.moveHorizontal(LocalDate.of(2027, 1, 31), -1, sun))
        // Top row, LEFT: Sun Nov 1 2026 (row 0) → October grid row 0 Sunday is Sep 27, clamp to Oct 4.
        assertEquals(LocalDate.of(2026, 10, 4), MonthNav.moveHorizontal(LocalDate.of(2026, 11, 1), -1, sun))
        // Monday week start: Sunday is the last column.
        val mon = DayOfWeek.MONDAY
        // Sun Oct 25 is row 3 of a Monday-start October (grid starts Sep 28); November starts Oct 26 → Nov 22.
        assertEquals(LocalDate.of(2026, 11, 22), MonthNav.moveHorizontal(LocalDate.of(2026, 10, 25), 1, mon))
        assertEquals(LocalDate.of(2026, 10, 27), MonthNav.moveHorizontal(LocalDate.of(2026, 10, 26), 1, mon))
    }

    @Test fun `month navigation`() {
        assertEquals(LocalDate.of(2026, 11, 3), MonthNav.move(today, 0, 1))
        assertEquals(LocalDate.of(2026, 10, 26), MonthNav.move(today, -1, 0))
        assertEquals(LocalDate.of(2026, 2, 28), MonthNav.shiftMonth(LocalDate.of(2026, 1, 31), 1))
    }

    @Test fun `detail and status text`() {
        assertEquals("Wed, Oct 28\n9:30 AM – 10:30 AM", fmt.detailWhen(events[0].time, zone, today))
        assertEquals("Wed, Oct 28 – Thu, Oct 29\nAll day", fmt.detailWhen(events[1].time, zone, today))
        assertEquals("Wed, Oct 28 10:00 PM –\nThu, Oct 29 2:00 AM", fmt.detailWhen(events[2].time, zone, today))
        val now = Instant.parse("2026-10-27T15:00:00Z").toEpochMilli()
        val earlier = Instant.parse("2026-10-27T14:42:00Z").toEpochMilli()
        assertEquals("Synced 10:42 AM", fmt.feedStatus(true, earlier, null, now, zone))
        assertEquals("Error: No connection · last good 10:42 AM", fmt.feedStatus(true, earlier, "No connection", now, zone))
        assertEquals("Error: No connection · no data yet", fmt.feedStatus(true, null, "No connection", now, zone))
        assertEquals("Off · Never synced", fmt.feedStatus(false, null, null, now, zone))
        assertEquals("Synced yesterday 10:42 AM", fmt.synced(earlier - 86_400_000, now, zone))
        assertEquals("Fri, Jan 1, 2027", fmt.dayHeader(LocalDate.of(2027, 1, 1), today))
    }

    @Test fun `24 hour format`() {
        assertEquals("09:30", Formats(Locale.UK, use24h = true).rowTime(events[0].time, LocalDate.of(2026, 10, 28), zone))
    }
}
