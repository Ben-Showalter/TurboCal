package com.turbocal.core.ics

import com.turbocal.core.TimeZoneRule
import com.turbocal.core.model.EventTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ExpanderTest {
    @get:Rule val tz = TimeZoneRule("America/New_York")
    private val ny = ZoneId.of("America/New_York")

    private fun timed(s: String, e: String) = EventTime.Timed(Instant.parse(s), Instant.parse(e))

    @Test fun `weekly series drops the EXDATE and moves the overridden instance`() {
        val got = Fixtures.read("weekly_moved_deleted.ics")
        assertEquals(
            listOf(
                timed("2026-10-05T14:00:00Z", "2026-10-05T15:00:00Z"),
                timed("2026-10-12T14:00:00Z", "2026-10-12T15:00:00Z"),
                // Oct 19 deleted; Oct 26 moved to Wed Oct 28 at 14:00
                timed("2026-10-28T18:00:00Z", "2026-10-28T19:00:00Z"),
                // after the Nov 1 DST change, 10:00 is 15:00Z
                timed("2026-11-02T15:00:00Z", "2026-11-02T16:00:00Z"),
                timed("2026-11-09T15:00:00Z", "2026-11-09T16:00:00Z"),
                timed("2026-11-16T15:00:00Z", "2026-11-16T16:00:00Z"),
                timed("2026-11-23T15:00:00Z", "2026-11-23T16:00:00Z"),
            ),
            got.map { it.time },
        )
        val moved = got[2]
        assertEquals("Standup (moved)", moved.title)
        assertEquals("Room 4", moved.location) // inherited from the master
        assertEquals("Weekly sync, bring notes", got[0].description) // ical4j unescaped it
        assertEquals("standup@test", moved.uid)
        assertEquals(got.size, got.map { it.recurrenceKey }.toSet().size)
    }

    @Test fun `cancelled override hides one instance and cancelled master hides the series`() {
        val got = Fixtures.read("cancelled.ics")
        assertEquals(listOf("Daily", "Daily"), got.map { it.title })
        assertEquals(
            listOf(
                timed("2026-11-10T14:00:00Z", "2026-11-10T15:00:00Z"),
                timed("2026-11-12T14:00:00Z", "2026-11-12T15:00:00Z"),
            ),
            got.map { it.time },
        )
    }

    @Test fun `Outlook Windows TZID with VTIMEZONE, without VTIMEZONE, and Olson without VTIMEZONE`() {
        val got = Fixtures.read("timezones.ics").associateBy({ it.uid }, { it.time })
        assertEquals(timed("2026-10-28T16:00:00Z", "2026-10-28T17:00:00Z"), got["outlook-pdt@test"])
        assertEquals(timed("2026-11-04T17:00:00Z", "2026-11-04T18:00:00Z"), got["outlook-pst@test"])
        assertEquals(timed("2026-11-04T14:00:00Z", "2026-11-04T14:30:00Z"), got["windows-no-vtimezone@test"])
        assertEquals(timed("2026-11-04T09:00:00Z", "2026-11-04T10:00:00Z"), got["olson-no-vtimezone@test"])
    }

    @Test fun `UTC is an instant and floating is wall time`() {
        val got = Fixtures.read("utc_floating.ics").associateBy({ it.uid }, { it.time })
        assertEquals(timed("2026-11-05T15:00:00Z", "2026-11-05T16:00:00Z"), got["utc@test"])
        val floating = got["floating@test"] as EventTime.Floating
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 0), floating.start)
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 30), floating.end)
    }

    @Test fun `floating time follows the phone's zone`() {
        val floating = Fixtures.read("utc_floating.ics").first { it.uid == "floating@test" }.time
        assertEquals(Instant.parse("2026-11-05T14:00:00Z"), floating.startInstant(ZoneId.of("America/New_York")))
        assertEquals(Instant.parse("2026-11-05T17:00:00Z"), floating.startInstant(ZoneId.of("America/Los_Angeles")))
    }

    @Test fun `floating time parses the same when the phone is in another zone`() {
        val saved = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            val floating = Fixtures.read("utc_floating.ics", ZoneId.of("Asia/Tokyo"))
                .first { it.uid == "floating@test" }.time as EventTime.Floating
            assertEquals(LocalDateTime.of(2026, 11, 5, 9, 0), floating.start)
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    @Test fun `all-day DTEND is exclusive and never spills into the next day`() {
        val got = Fixtures.read("allday.ics").associateBy({ it.uid }, { it.time })
        val one = got["one-day@test"] as EventTime.AllDay
        assertEquals(EventTime.AllDay(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6)), one)
        assertTrue(one.occursOn(LocalDate.of(2026, 11, 5), ny))
        assertFalse(one.occursOn(LocalDate.of(2026, 11, 6), ny))
        assertFalse(one.occursOn(LocalDate.of(2026, 11, 4), ny))
        assertEquals(LocalDate.of(2026, 11, 5), one.lastDay(ny))

        val three = got["three-day@test"] as EventTime.AllDay
        assertEquals((10..12).map { LocalDate.of(2026, 11, it) }, (9..13).map { LocalDate.of(2026, 11, it) }.filter { three.occursOn(it, ny) })

        assertEquals(EventTime.AllDay(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 21)), got["no-end@test"])
    }

    @Test fun `all-day dates do not shift with the phone's zone`() {
        val one = Fixtures.read("allday.ics", ZoneId.of("Pacific/Honolulu")).first { it.uid == "one-day@test" }.time
        assertEquals(EventTime.AllDay(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6)), one)
        assertTrue(one.occursOn(LocalDate.of(2026, 11, 5), ZoneId.of("Pacific/Kiritimati")))
    }

    @Test fun `yearly all-day series expands only inside the window`() {
        val starts = Fixtures.read("allday.ics").filter { it.uid == "yearly@test" }.map { (it.time as EventTime.AllDay).start }
        assertEquals(listOf(LocalDate.of(2027, 1, 1)), starts)
    }

    @Test fun `1_30 on the fall-back day is the first occurrence and lasts an hour`() {
        val got = Fixtures.read("dst_2026.ics").first { it.uid == "ambiguous@test" }.time as EventTime.Timed
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), got.start) // 1:30 EDT
        assertEquals(Duration.ofHours(1), Duration.between(got.start, got.end))
    }

    @Test fun `daily recurrence keeps its wall-clock time across the DST change`() {
        val got = Fixtures.read("dst_2026.ics").filter { it.uid == "daily-9@test" }.map { it.time.startInstant(ny) }
        assertEquals(
            listOf("2026-10-30T13:00:00Z", "2026-10-31T13:00:00Z", "2026-11-01T14:00:00Z", "2026-11-02T14:00:00Z").map(Instant::parse),
            got,
        )
        assertTrue(got.all { it.atZone(ny).hour == 9 })
    }

    @Test fun `overnight event across the change has its real length and shows on both days`() {
        val t = Fixtures.read("dst_2026.ics").first { it.uid == "overnight@test" }.time
        // 22:00 EDT to 03:00 EST is six real hours.
        assertEquals(Duration.ofHours(6), Duration.between(t.startInstant(ny), t.endInstant(ny)))
        assertTrue(t.occursOn(LocalDate.of(2026, 10, 31), ny))
        assertTrue(t.occursOn(LocalDate.of(2026, 11, 1), ny))
        assertFalse(t.occursOn(LocalDate.of(2026, 11, 2), ny))
    }

    @Test fun `timed event crossing midnight shows on both days and no others`() {
        val t = Fixtures.read("midnight.ics").single().time
        assertEquals(LocalDate.of(2026, 11, 12), t.firstDay(ny))
        assertEquals(LocalDate.of(2026, 11, 13), t.lastDay(ny))
        assertFalse(t.occursOn(LocalDate.of(2026, 11, 14), ny))
    }

    @Test fun `window keeps 60 days back and 365 forward`() {
        val w = ExpansionWindow.around(Fixtures.TODAY, ny)
        assertEquals(LocalDate.of(2026, 8, 28), w.from)
        assertEquals(LocalDate.of(2027, 10, 28), w.toExclusive)
    }

    @Test(expected = NotICalendarException::class)
    fun `a web page is not a calendar`() {
        Fixtures.read("not_calendar.html")
    }

    @Test(expected = NotICalendarException::class)
    fun `garbage is not a calendar`() {
        FeedReader.read("hello world".byteInputStream(), ExpansionWindow.around(Fixtures.TODAY, ny))
    }
}
