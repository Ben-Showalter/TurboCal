package com.turbocal.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class EventTimeTest {
    @Test fun `keys round-trip for every kind`() {
        val cases = listOf(
            EventTime.AllDay(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6)),
            EventTime.Timed(Instant.parse("2026-11-01T05:30:00Z"), Instant.parse("2026-11-01T06:30:00Z")),
            EventTime.Floating(LocalDateTime.of(2026, 11, 5, 9, 0), LocalDateTime.of(2026, 11, 5, 9, 30)),
            EventTime.AllDay(LocalDate.of(1965, 1, 1), LocalDate.of(1965, 1, 2)),
        )
        for (c in cases) assertEquals(c, EventTime.fromKeys(c.kind, c.startKey, c.endKey))
    }

    @Test fun `key is within the slack of the real start in any zone`() {
        val t = EventTime.AllDay(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6))
        for (id in listOf("Pacific/Kiritimati", "Etc/GMT+12", "America/New_York", "UTC")) {
            val real = t.startInstant(ZoneId.of(id)).toEpochMilli()
            assertTrue(id, Math.abs(real - t.startKey) <= EventTime.KEY_SLACK_MILLIS)
        }
    }
}
