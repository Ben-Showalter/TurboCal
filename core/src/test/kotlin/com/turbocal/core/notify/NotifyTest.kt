package com.turbocal.core.notify

import com.turbocal.core.display.DisplayEvent
import com.turbocal.core.display.Formats
import com.turbocal.core.model.EventTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class NotifyTest {
    private val ny = ZoneId.of("America/New_York")
    private val fmt = Formats(Locale.US, use24h = false)
    private val s = ReminderSettings() // on, 5 min before, all-day 8:00 AM

    private fun ev(id: Long, title: String, time: EventTime, location: String = "") =
        DisplayEvent(id, 1, "Cal", 1, title, location, "", time)

    private fun at(start: String, end: String) = EventTime.Timed(Instant.parse(start), Instant.parse(end))

    @Test fun `defaults are on, five minutes before, all-day at 8`() {
        assertTrue(s.enabled)
        assertEquals(5, s.leadMinutes)
        assertEquals(LocalTime.of(8, 0), s.allDayTime)
    }

    @Test fun `notify times for timed, floating and all-day events`() {
        val timed = ev(1, "Dentist", at("2026-10-28T13:30:00Z", "2026-10-28T14:30:00Z"))
        assertEquals(Instant.parse("2026-10-28T13:25:00Z"), Reminders.notifyAt(timed, s, ny))
        val floating = ev(2, "Alarm", EventTime.Floating(LocalDateTime.of(2026, 10, 28, 9, 0), LocalDateTime.of(2026, 10, 28, 9, 30)))
        assertEquals(Instant.parse("2026-10-28T12:55:00Z"), Reminders.notifyAt(floating, s, ny))
        // Multi-day all-day: first day at 8:00 local, no lead.
        val trip = ev(3, "Trip", EventTime.AllDay(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 8)))
        assertEquals(Instant.parse("2026-11-05T13:00:00Z"), Reminders.notifyAt(trip, s, ny))
        assertEquals(Instant.parse("2026-10-28T13:30:00Z"), Reminders.notifyAt(timed, s.copy(leadMinutes = 0), ny))
    }

    @Test fun `all-day time follows the DST change`() {
        val before = ev(1, "Sat", EventTime.AllDay(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1)))
        val after = ev(2, "Sun", EventTime.AllDay(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)))
        assertEquals(Instant.parse("2026-10-31T12:00:00Z"), Reminders.notifyAt(before, s, ny)) // EDT
        assertEquals(Instant.parse("2026-11-01T13:00:00Z"), Reminders.notifyAt(after, s, ny)) // EST
    }

    @Test fun `events notifying at the same instant are grouped, earlier ones excluded`() {
        val a = ev(1, "Standup", at("2026-10-28T14:00:00Z", "2026-10-28T14:15:00Z"))
        val b = ev(2, "Call Mom", at("2026-10-28T14:00:00Z", "2026-10-28T14:30:00Z"))
        val c = ev(3, "Lunch", at("2026-10-28T16:00:00Z", "2026-10-28T17:00:00Z"))
        val g = Reminders.nextGroup(listOf(c, b, a), s, ny, Instant.parse("2026-10-28T12:00:00Z"))!!
        assertEquals(Instant.parse("2026-10-28T13:55:00Z"), g.at)
        assertEquals(setOf("Standup", "Call Mom"), g.events.map { it.title }.toSet())
        // Once that group has fired, the next is Lunch.
        val next = Reminders.nextGroup(listOf(a, b, c), s, ny, g.at)!!
        assertEquals(listOf("Lunch"), next.events.map { it.title })
        assertNull(Reminders.nextGroup(listOf(a, b, c), s.copy(enabled = false), ny, Instant.EPOCH))
        assertEquals(2, Reminders.groupAt(listOf(a, b, c), s, ny, g.at)!!.events.size)
    }

    @Test fun `text for one event, several, and all-day`() {
        val one = ReminderGroup(Instant.parse("2026-10-28T13:25:00Z"), listOf(ev(1, "Dentist", at("2026-10-28T13:30:00Z", "2026-10-28T14:30:00Z"), "Room 4")))
        val t1 = ReminderTexts.build(one, s, ny, fmt)
        assertEquals("Dentist in 5 min", t1.title)
        assertEquals("9:30 AM · Room 4", t1.text)
        assertEquals("Dentist in 5 min", t1.cover)

        val two = ReminderGroup(
            Instant.parse("2026-10-28T13:55:00Z"),
            listOf(ev(1, "Standup", at("2026-10-28T14:00:00Z", "2026-10-28T14:15:00Z")), ev(2, "Call Mom", at("2026-10-28T14:00:00Z", "2026-10-28T14:30:00Z"))),
        )
        val t2 = ReminderTexts.build(two, s, ny, fmt)
        assertEquals("2 events in 5 min", t2.title)
        assertEquals("Standup, Call Mom", t2.text)
        assertEquals("2 events: Standup, Call Mom", t2.cover)
        assertEquals(listOf("10:00 AM  Standup", "10:00 AM  Call Mom"), t2.lines)
        assertEquals("2 events at 10:00 AM", ReminderTexts.build(two, s.copy(leadMinutes = 0), ny, fmt).title)

        val day = LocalDate.of(2026, 10, 28)
        val allDay = ReminderGroup(
            Instant.parse("2026-10-28T12:00:00Z"),
            listOf(ev(1, "Brute Upgrade", EventTime.AllDay(day, day.plusDays(1))), ev(2, "Fair", EventTime.AllDay(day, day.plusDays(1)))),
        )
        val t3 = ReminderTexts.build(allDay, s, ny, fmt)
        assertEquals("2 all-day events today", t3.title)
        // A single all-day event has no lead time in its title.
        val single = ReminderTexts.build(allDay.copy(events = allDay.events.take(1)), s, ny, fmt)
        assertEquals("Brute Upgrade", single.title)
        assertEquals("All day", single.text)
    }

    @Test fun `cover ticker helpers`() {
        assertEquals("9:25 AM · Dentist", coverLine("9:25 AM", "Dentist", 120))
        assertEquals("abcd…", coverDisplayText("abcdefgh", 5))
        assertEquals(listOf("short"), tickerFrames("short", 9, 3, 2))
        val frames = tickerFrames("0123456789ABCDEF", 9, 2, 2)
        assertEquals("012345678", frames.first())
        assertEquals("789ABCDEF", frames.last())
        assertEquals(frames.size * 2, tickerFrameCount(frames.size, 400, frames.size * 400L + 1))
        assertTrue(shouldReshow(true, 16_000, 15_000))
        assertFalse(shouldReshow(true, 1_000, 15_000))
    }
}
