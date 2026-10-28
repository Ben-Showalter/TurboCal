package com.turbocal.core.local

import com.turbocal.core.TimeZoneRule
import com.turbocal.core.ics.ExpansionWindow
import com.turbocal.core.model.EventTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class LocalCalendarTest {
    @get:Rule val tz = TimeZoneRule("America/New_York")
    private val ny = ZoneId.of("America/New_York")
    private val window = ExpansionWindow.around(LocalDate.of(2026, 10, 27), ny)

    private val weekly = LocalEvent(
        uid = "local-1", title = "Choir", location = "Hall", description = "Bring music",
        allDay = false,
        start = LocalDateTime.of(2026, 10, 25, 9, 30), end = LocalDateTime.of(2026, 10, 25, 10, 30),
        rrule = RepeatChoice.WEEKLY.rrule,
        exdates = listOf(LocalDateTime.of(2026, 11, 8, 9, 30)),
    )

    private val trip = LocalEvent(
        uid = "local-2", title = "Trip", location = "", description = "",
        allDay = true,
        // Nov 5 to Nov 7 inclusive, stored with an exclusive end like iCalendar.
        start = LocalDate.of(2026, 11, 5).atStartOfDay(), end = LocalDate.of(2026, 11, 8).atStartOfDay(),
        rrule = null,
    )

    @Test fun `weekly floating event keeps its wall time across DST and honours the EXDATE`() {
        val got = LocalCalendar.expand(listOf(weekly), window).filter { it.uid == "local-1" }
        val starts = got.take(4).map { (it.time as EventTime.Floating).start }
        assertEquals(
            listOf(
                LocalDateTime.of(2026, 10, 25, 9, 30),
                LocalDateTime.of(2026, 11, 1, 9, 30), // the DST change day
                // Nov 8 deleted
                LocalDateTime.of(2026, 11, 15, 9, 30),
                LocalDateTime.of(2026, 11, 22, 9, 30),
            ),
            starts,
        )
        assertTrue(got.all { (it.time as EventTime.Floating).start.hour == 9 })
        assertEquals("Hall", got[0].location)
    }

    @Test fun `all-day end is exclusive and shows on the inclusive days only`() {
        val t = LocalCalendar.expand(listOf(trip), window).single().time as EventTime.AllDay
        assertEquals(LocalDate.of(2026, 11, 5), t.start)
        assertEquals(LocalDate.of(2026, 11, 8), t.endExclusive)
        assertFalse(t.occursOn(LocalDate.of(2026, 11, 8), ny))
        assertTrue(t.occursOn(LocalDate.of(2026, 11, 7), ny))
    }

    @Test fun `export then import round-trips, custom rule survives`() {
        val custom = weekly.copy(uid = "local-3", rrule = "FREQ=WEEKLY;BYDAY=MO,WE;COUNT=6")
        val ics = LocalCalendar.toIcs(listOf(weekly, trip, custom))
        assertTrue(ics.startsWith("BEGIN:VCALENDAR"))
        val back = LocalCalendar.fromIcs(ics.byteInputStream(), ny)
        assertEquals(0, back.skipped)
        val byUid = back.events.associateBy { it.uid }
        assertEquals(weekly, byUid["local-1"])
        assertEquals(trip, byUid["local-2"])
        assertEquals(null, RepeatChoice.of(byUid["local-3"]!!.rrule))
        assertEquals(6, LocalCalendar.expand(listOf(byUid["local-3"]!!), window).size)
    }

    @Test fun `import converts zoned times to the phone's wall time and skips overrides`() {
        val feed = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//t//EN",
            "BEGIN:VEVENT", "UID:z@test", "DTSTAMP:20261001T000000Z",
            "DTSTART:20261105T150000Z", "DTEND:20261105T160000Z", "SUMMARY:Zoned", "END:VEVENT",
            "BEGIN:VEVENT", "UID:z@test", "DTSTAMP:20261001T000000Z", "RECURRENCE-ID:20261112T150000Z",
            "DTSTART:20261112T170000Z", "DTEND:20261112T180000Z", "SUMMARY:Moved", "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n")
        val r = LocalCalendar.fromIcs(feed.byteInputStream(), ny)
        assertEquals(1, r.skipped)
        assertEquals(LocalDateTime.of(2026, 11, 5, 10, 0), r.events.single().start)
    }

    @Test fun `occurrence keys map back to the start to exclude`() {
        val inst = LocalCalendar.expand(listOf(weekly), window)[1]
        val start = LocalCalendar.occurrenceStart(inst.recurrenceKey)
        assertEquals(LocalDateTime.of(2026, 11, 1, 9, 30), start)
        val after = LocalCalendar.expand(listOf(weekly.copy(exdates = weekly.exdates + start!!)), window)
        assertFalse(after.any { (it.time as EventTime.Floating).start == start })
        assertEquals(LocalDate.of(2026, 11, 5).atStartOfDay(), LocalCalendar.occurrenceStart("D:2026-11-05"))
        assertNull(LocalCalendar.occurrenceStart(""))
        assertNull(LocalCalendar.occurrenceStart("T:123"))
    }

    @Test fun `repeat choices map both ways`() {
        assertEquals(RepeatChoice.NONE, RepeatChoice.of(null))
        assertEquals(RepeatChoice.MONTHLY, RepeatChoice.of("FREQ=MONTHLY"))
        assertNull(RepeatChoice.of("FREQ=DAILY;INTERVAL=2"))
        assertTrue(LocalCalendar.isValidRule("FREQ=DAILY;INTERVAL=2"))
        assertFalse(LocalCalendar.isValidRule("NOT A RULE"))
    }
}
