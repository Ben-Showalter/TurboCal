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

    @Test fun `month grid is six weeks from the first weekday with dots`() {
        val g = MonthGrid.build(YearMonth.of(2026, 10), DayOfWeek.SUNDAY, today, events, zone)
        assertEquals(42, g.cells.size)
        assertEquals(LocalDate.of(2026, 9, 27), g.gridStart)
        assertEquals(DayOfWeek.SUNDAY, g.gridStart.dayOfWeek)
        val oct28 = g.cells.first { it.date == LocalDate.of(2026, 10, 28) }
        assertEquals(listOf(1), oct28.colors)
        assertTrue(g.cells.first { it.date == today }.isToday)
        assertTrue(g.cells.first { it.date == LocalDate.of(2026, 10, 30) }.colors.isEmpty()) // DTEND exclusive
        val monday = MonthGrid.range(YearMonth.of(2026, 11), DayOfWeek.MONDAY).first
        assertEquals(LocalDate.of(2026, 10, 26), monday)
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
