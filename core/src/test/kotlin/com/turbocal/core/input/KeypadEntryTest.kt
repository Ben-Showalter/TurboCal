package com.turbocal.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class KeypadEntryTest {
    private fun KeypadEntry.typeAll(s: String) = s.forEach { type(it - '0') }

    @Test fun `typing a full US date moves through month, day and year`() {
        val e = KeypadEntry.forDate(LocalDate.of(2026, 10, 27))
        e.typeAll("11052026")
        assertEquals(LocalDate.of(2026, 11, 5), e.date())
    }

    @Test fun `a digit that can't start a two-digit value finishes the segment`() {
        val e = KeypadEntry.forDate(LocalDate.of(2026, 10, 27))
        e.typeAll("3") // month 3, moves on at once
        assertEquals(1, e.focus)
        e.typeAll("9") // day 9
        assertEquals(2, e.focus)
        assertEquals(LocalDate.of(2026, 3, 9), e.date())
    }

    @Test fun `too-big second digit spills into the next segment`() {
        val e = KeypadEntry.forDate(LocalDate.of(2026, 10, 27))
        e.typeAll("15") // "15" isn't a month: month 1, then day starts with 5
        assertEquals(LocalDate.of(2026, 1, 5), e.date())
    }

    @Test fun `day order and clamping to the month`() {
        val e = KeypadEntry.forDate(LocalDate.of(2026, 1, 31), order = "DMY")
        e.move(1)
        e.typeAll("02") // February: day 31 becomes 28
        assertEquals(LocalDate.of(2026, 2, 28), e.date())
        e.move(1)
        e.typeAll("28") // two-digit year
        assertEquals(LocalDate.of(2028, 2, 28), e.date())
    }

    @Test fun `up and down wrap within the segment`() {
        val e = KeypadEntry.forDate(LocalDate.of(2026, 12, 27))
        e.step(1)
        assertEquals(1, e.date().monthValue)
        e.move(1)
        e.step(-27)
        assertEquals(31, e.date().dayOfMonth)
    }

    @Test fun `12-hour time with T9 AM and PM`() {
        val e = KeypadEntry.forTime(LocalTime.of(9, 0), use24h = false)
        e.typeAll("230")  // hour 2, minute 30
        e.type(7)         // P
        assertEquals(LocalTime.of(14, 30), e.time())
        e.type(2)         // A
        assertEquals(LocalTime.of(2, 30), e.time())
        assertEquals("AM", e.text(2))
    }

    @Test fun `12 PM is noon and 12 AM is midnight`() {
        val noon = KeypadEntry.forTime(LocalTime.of(12, 15), use24h = false)
        assertEquals("12", noon.text(0))
        assertEquals("PM", noon.text(2))
        assertEquals(LocalTime.of(12, 15), noon.time())
        assertEquals(LocalTime.of(0, 0), KeypadEntry.forTime(LocalTime.MIDNIGHT, false).time())
    }

    @Test fun `24-hour time and backspace`() {
        val e = KeypadEntry.forTime(LocalTime.of(9, 0), use24h = true)
        e.type(1)
        assertEquals("1", e.text(0))
        assertTrue(e.backspace())
        assertFalse(e.backspace())
        e.typeAll("1745")
        assertEquals(LocalTime.of(17, 45), e.time())
    }
}
