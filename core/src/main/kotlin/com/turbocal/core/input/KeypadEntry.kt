package com.turbocal.core.input

import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * Number entry for the date and time pickers, keypad style (AGENTS.md §8).
 * A value is a row of segments (month / day / year, or hour / minute / AM-PM).
 * Digits fill the focused segment and move on by themselves once nothing more
 * could follow ("9" in the hour moves to the minutes; "1" waits for "0"–"2").
 * Up/down change the focused segment, left/right move between segments.
 */
class KeypadEntry private constructor(private val segments: List<Segment>, focus: Int = 0) {

    class Segment(
        val kind: Kind,
        val min: Int,
        var max: Int,
        var value: Int,
    ) {
        /** Digits typed into this segment but not yet complete. */
        var buffer: String = ""
        val width: Int get() = if (kind == Kind.YEAR) 4 else 2
    }

    enum class Kind { MONTH, DAY, YEAR, HOUR, MINUTE, AMPM }

    var focus: Int = focus
        private set

    val size: Int get() = segments.size
    fun kind(i: Int): Kind = segments[i].kind

    /** What segment [i] shows: the partial typing while typing, else the value. */
    fun text(i: Int): String {
        val s = segments[i]
        if (s.kind == Kind.AMPM) return if (s.value == 0) "AM" else "PM"
        if (s.buffer.isNotEmpty()) return s.buffer
        return if (s.kind == Kind.YEAR || s.kind == Kind.HOUR) s.value.toString() else s.value.toString().padStart(2, '0')
    }

    fun move(direction: Int) {
        commit()
        focus = (focus + direction).coerceIn(0, segments.size - 1)
    }

    /** Up/down: one step, wrapping inside the segment's range. */
    fun step(delta: Int) {
        commit()
        val s = segments[focus]
        val span = s.max - s.min + 1
        s.value = s.min + Math.floorMod(s.value - s.min + delta, span)
        clampDay()
    }

    /** One typed digit. On AM/PM, 2 (ABC) means AM and 7 (PQRS) means PM, like T9. */
    fun type(digit: Int) {
        val s = segments[focus]
        if (s.kind == Kind.AMPM) {
            when (digit) {
                2 -> s.value = 0
                7 -> s.value = 1
            }
            return
        }
        val candidate = s.buffer + digit
        val n = candidate.toInt()
        when {
            // Doesn't fit: keep what was there, start the next segment with this digit.
            s.buffer.isNotEmpty() && n > s.max -> {
                commit()
                if (focus < segments.size - 1) {
                    focus++
                    type(digit)
                }
            }
            else -> {
                s.buffer = candidate
                // Complete when full width, or when no further digit could still fit.
                if (candidate.length >= s.width || n * 10 > s.max) {
                    commit()
                    if (focus < segments.size - 1) focus++
                }
            }
        }
    }

    /** Clear key: undo the last typed digit. False when there was nothing to undo. */
    fun backspace(): Boolean {
        val s = segments[focus]
        if (s.buffer.isEmpty()) return false
        s.buffer = s.buffer.dropLast(1)
        return true
    }

    /** Finishes any partial typing in the focused segment (a lone "1" becomes 1). */
    fun commit() {
        val s = segments[focus]
        if (s.buffer.isEmpty()) return
        val n = s.buffer.toInt()
        s.buffer = ""
        // A typed "0" for a 1-based field, or a 2-digit year, is completed sensibly.
        s.value = when {
            s.kind == Kind.YEAR && n < 100 -> 2000 + n
            else -> n
        }.coerceIn(s.min, s.max)
        clampDay()
    }

    /** Keeps the day valid for the chosen month and year (Feb 30 → Feb 28/29). */
    private fun clampDay() {
        val day = segments.firstOrNull { it.kind == Kind.DAY } ?: return
        val month = segments.firstOrNull { it.kind == Kind.MONTH }?.value ?: return
        val year = segments.firstOrNull { it.kind == Kind.YEAR }?.value ?: return
        day.max = YearMonth.of(year, month).lengthOfMonth()
        if (day.value > day.max) day.value = day.max
    }

    fun date(): LocalDate {
        commit()
        val v = segments.associate { it.kind to it.value }
        return LocalDate.of(v.getValue(Kind.YEAR), v.getValue(Kind.MONTH), v.getValue(Kind.DAY))
    }

    fun time(): LocalTime {
        commit()
        val v = segments.associate { it.kind to it.value }
        val hour = v.getValue(Kind.HOUR)
        val ampm = v[Kind.AMPM] ?: return LocalTime.of(hour, v.getValue(Kind.MINUTE))
        val h24 = (hour % 12) + if (ampm == 1) 12 else 0
        return LocalTime.of(h24, v.getValue(Kind.MINUTE))
    }

    companion object {
        /** [order] is the locale's date field order, e.g. "MDY" (US) or "DMY". */
        fun forDate(date: LocalDate, order: String = "MDY"): KeypadEntry {
            val segs = order.uppercase().mapNotNull { c ->
                when (c) {
                    'M' -> Segment(Kind.MONTH, 1, 12, date.monthValue)
                    'D' -> Segment(Kind.DAY, 1, date.lengthOfMonth(), date.dayOfMonth)
                    'Y' -> Segment(Kind.YEAR, 1900, 2199, date.year)
                    else -> null
                }
            }
            require(segs.map { it.kind }.toSet().size == 3) { "order must contain M, D and Y" }
            return KeypadEntry(segs)
        }

        fun forTime(time: LocalTime, use24h: Boolean): KeypadEntry {
            if (use24h) {
                return KeypadEntry(listOf(Segment(Kind.HOUR, 0, 23, time.hour), Segment(Kind.MINUTE, 0, 59, time.minute)))
            }
            val h12 = if (time.hour % 12 == 0) 12 else time.hour % 12
            return KeypadEntry(
                listOf(
                    Segment(Kind.HOUR, 1, 12, h12),
                    Segment(Kind.MINUTE, 0, 59, time.minute),
                    Segment(Kind.AMPM, 0, 1, if (time.hour < 12) 0 else 1),
                ),
            )
        }
    }
}
