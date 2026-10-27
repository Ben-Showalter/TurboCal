package com.turbocal.core.ics

import com.turbocal.core.model.EventInstance
import com.turbocal.core.model.EventTime
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Date
import net.fortuna.ical4j.model.DateList
import net.fortuna.ical4j.model.DateTime
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.parameter.Value
import net.fortuna.ical4j.model.property.ExDate
import net.fortuna.ical4j.model.property.RDate
import net.fortuna.ical4j.model.property.RRule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAmount

/**
 * Expands a parsed calendar into the instances that overlap an [ExpansionWindow].
 *
 * The recurrence maths (RRULE) is ical4j's `Recur.getDates`. This class only
 * applies the RFC 5545 set rules around it: DTSTART is always the first
 * instance, RDATE adds, EXDATE removes, a RECURRENCE-ID component replaces the
 * instance it names, and STATUS:CANCELLED hides an instance or a whole series.
 */
object Expander {

    /** Guard against a pathological rule (FREQ=SECONDLY) filling the database. */
    private const val MAX_PER_SERIES = 3000

    fun expand(calendar: Calendar, window: ExpansionWindow): List<EventInstance> {
        val events = calendar.getComponents<VEvent>(Component.VEVENT)
        val byUid = LinkedHashMap<String, MutableList<VEvent>>()
        for (e in events) {
            if (e.startDate?.date == null) continue
            byUid.getOrPut(uidOf(e)) { mutableListOf() }.add(e)
        }
        val out = ArrayList<EventInstance>()
        for ((uid, group) in byUid) {
            val master = group.firstOrNull { it.recurrenceId == null }
            val overrides = group.filter { it.recurrenceId != null }
            out += expandSeries(uid, master, overrides, window)
        }
        out.sortWith(compareBy({ it.time.startKey }, { it.title }))
        return out
    }

    private fun expandSeries(
        uid: String,
        master: VEvent?,
        overrides: List<VEvent>,
        window: ExpansionWindow,
    ): List<EventInstance> {
        if (master != null && isCancelled(master)) return emptyList()

        val overrideByKey = LinkedHashMap<String, VEvent>()
        for (o in overrides) overrideByKey[Point.of(o.recurrenceId.date).key] = o

        val result = ArrayList<EventInstance>()
        val used = HashSet<String>()

        if (master != null) {
            val recurring = master.getProperties<RRule>(Property.RRULE).isNotEmpty() ||
                master.getProperties<RDate>(Property.RDATE).isNotEmpty()
            val masterTime = timeOf(master, null) ?: return emptyList()
            val duration = Span.of(masterTime)
            val starts = if (recurring) occurrences(master, masterTime, duration, window) else listOf(Point.of(master.startDate.date))
            for (start in starts) {
                val key = if (recurring) start.key else ""
                val override = if (recurring) overrideByKey[start.key] else null
                if (override != null) {
                    used += start.key
                    if (isCancelled(override)) continue
                    val t = timeOf(override, duration) ?: continue
                    if (t.overlaps(window.fromInstant, window.toInstant, window.zone)) {
                        result += instanceOf(uid, key, override, master, t)
                    }
                } else {
                    val t = duration.from(start)
                    if (t.overlaps(window.fromInstant, window.toInstant, window.zone)) {
                        result += instanceOf(uid, key, master, null, t)
                    }
                }
            }
        }

        // Overrides whose original slot was outside the window (or with no master
        // at all, as Google sends for a single invited instance) still count if the
        // moved instance itself lands in the window.
        for ((key, o) in overrideByKey) {
            if (key in used || isCancelled(o)) continue
            val t = timeOf(o, master?.let { timeOf(it, null) }?.let { Span.of(it) }) ?: continue
            if (t.overlaps(window.fromInstant, window.toInstant, window.zone)) {
                result += instanceOf(uid, key, o, master, t)
            }
        }
        return result
    }

    /** Start points of every instance of [master] that could overlap [window]. */
    private fun occurrences(master: VEvent, masterTime: EventTime, duration: Span, window: ExpansionWindow): List<Point> {
        val seed = master.startDate.date
        val (periodStart, periodEnd, value) = when (masterTime) {
            is EventTime.AllDay -> Triple(
                Date(toIcalDate(window.from.minusDays(duration.days + 1))),
                Date(toIcalDate(window.toExclusive.plusDays(1))),
                Value.DATE,
            )
            else -> Triple(
                DateTime(window.fromInstant.toEpochMilli() - duration.maxMillis - DAY_MS),
                DateTime(window.toInstant.toEpochMilli() + DAY_MS),
                Value.DATE_TIME,
            )
        }

        val points = LinkedHashMap<String, Point>()
        // DTSTART is always the first instance, whether or not it matches the rule.
        Point.of(seed).let { points[it.key] = it }
        for (rrule in master.getProperties<RRule>(Property.RRULE)) {
            val dates: DateList = rrule.recur.getDates(seed, periodStart, periodEnd, value, MAX_PER_SERIES)
            for (d in dates) Point.of(d).let { points[it.key] = it }
        }
        for (rdate in master.getProperties<RDate>(Property.RDATE)) {
            for (d in rdate.dates ?: continue) Point.of(d).let { points[it.key] = it }
        }
        for (exdate in master.getProperties<ExDate>(Property.EXDATE)) {
            for (d in exdate.dates) {
                val ex = Point.of(d)
                points.remove(ex.key)
                // An all-day EXDATE on a timed series removes that whole day.
                if (ex is Point.Day) points.values.removeAll { it.dayIn(window) == ex.date }
            }
        }
        return points.values.toList()
    }

    private fun instanceOf(uid: String, key: String, e: VEvent, fallback: VEvent?, time: EventTime) = EventInstance(
        uid = uid,
        recurrenceKey = key,
        title = text(e.summary?.value) ?: text(fallback?.summary?.value).orEmpty(),
        location = text(e.location?.value) ?: text(fallback?.location?.value).orEmpty(),
        description = text(e.description?.value) ?: text(fallback?.description?.value).orEmpty(),
        time = time,
    )

    /** The time of a single component; [fallbackSpan] supplies a missing end for overrides. */
    private fun timeOf(e: VEvent, fallbackSpan: Span?): EventTime? {
        val start = Point.of(e.startDate?.date ?: return null)
        val explicitEnd = e.getEndDate(false)?.date?.let { Point.of(it) }
        if (explicitEnd != null) {
            Span.between(start, explicitEnd)?.let { return it.from(start) }
        }
        // DURATION is applied to our own resolved start, not ical4j's derived DTEND.
        e.duration?.duration?.let { amount -> Span.ofAmount(start, amount)?.let { return it.from(start) } }
        return (fallbackSpan?.takeIf { it.matches(start) } ?: Span.defaultFor(start)).from(start)
    }

    private fun isCancelled(e: VEvent) = e.status?.value.equals("CANCELLED", ignoreCase = true)

    private fun uidOf(e: VEvent): String = e.uid?.value?.takeIf { it.isNotBlank() }
        ?: "nouid-${e.startDate?.value}-${e.summary?.value}".hashCode().toString()

    private fun text(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }

    private fun toIcalDate(d: LocalDate): String = d.format(DateTimeFormatter.BASIC_ISO_DATE)

    private const val DAY_MS = 86_400_000L

    /** One instant-ish start point, in the same three kinds as [EventTime]. */
    private sealed class Point {
        abstract val key: String
        abstract fun dayIn(window: ExpansionWindow): LocalDate

        data class Day(val date: LocalDate) : Point() {
            override val key get() = "D:$date"
            override fun dayIn(window: ExpansionWindow) = date
        }

        data class At(val instant: Instant) : Point() {
            override val key get() = "T:${instant.toEpochMilli()}"
            override fun dayIn(window: ExpansionWindow): LocalDate = instant.atZone(window.zone).toLocalDate()
        }

        data class Wall(val time: LocalDateTime) : Point() {
            override val key get() = "F:$time"
            override fun dayIn(window: ExpansionWindow): LocalDate = time.toLocalDate()
        }

        companion object {
            private val WALL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

            fun of(d: Date): Point = when {
                d !is DateTime -> Day(LocalDate.parse(d.toString().take(8), DateTimeFormatter.BASIC_ISO_DATE))
                d.isUtc -> At(Instant.ofEpochMilli(d.time))
                d.timeZone != null -> At(zonedInstant(d))
                else -> Wall(LocalDateTime.parse(d.toString().take(15), WALL))
            }

            /**
             * A zoned wall time is resolved with java.time when the TZID names a
             * zone the phone knows (directly or as a Windows name), because
             * java.time follows RFC 5545 for the ambiguous fall-back hour (first
             * occurrence) and the skipped spring-forward hour. ical4j's
             * VTIMEZONE-based zone picks the second occurrence. Unknown TZIDs
             * fall back to the feed's own VTIMEZONE via ical4j.
             */
            private fun zonedInstant(d: DateTime): Instant {
                val zone = javaZone(d.timeZone.id) ?: return Instant.ofEpochMilli(d.time)
                val wall = LocalDateTime.parse(d.toString().take(15), WALL)
                return ZonedDateTime.of(wall, zone).toInstant()
            }

            private fun javaZone(tzid: String?): ZoneId? {
                if (tzid.isNullOrBlank()) return null
                val id = tzid.trim().removePrefix("/")
                return runCatching { ZoneId.of(id) }.getOrNull()
                    ?: WindowsZones.toIana(id)?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            }
        }
    }

    /** An event's length, kept in the unit that suits its kind. */
    private sealed class Span {
        abstract fun from(start: Point): EventTime
        abstract fun matches(start: Point): Boolean
        abstract val days: Long
        abstract val maxMillis: Long

        data class Days(override val days: Long) : Span() {
            override val maxMillis get() = days * DAY_MS
            override fun matches(start: Point) = start is Point.Day
            override fun from(start: Point): EventTime {
                val d = (start as Point.Day).date
                return EventTime.AllDay(d, d.plusDays(days))
            }
        }

        data class Exact(val length: Duration) : Span() {
            override val days get() = length.toDays()
            override val maxMillis get() = length.toMillis()
            override fun matches(start: Point) = start !is Point.Day
            override fun from(start: Point): EventTime = when (start) {
                is Point.At -> EventTime.Timed(start.instant, start.instant.plus(length))
                is Point.Wall -> EventTime.Floating(start.time, start.time.plus(length))
                is Point.Day -> EventTime.AllDay(start.date, start.date.plusDays(1))
            }
        }

        companion object {
            fun of(t: EventTime): Span = when (t) {
                is EventTime.AllDay -> Days(maxOf(1, t.endExclusive.toEpochDay() - t.start.toEpochDay()))
                is EventTime.Timed -> Exact(Duration.between(t.start, t.end))
                is EventTime.Floating -> Exact(Duration.between(t.start, t.end))
            }

            /** RFC 5545: days and weeks in DURATION are nominal, hours/minutes/seconds exact. */
            fun ofAmount(start: Point, amount: TemporalAmount): Span? {
                val span = when (start) {
                    is Point.Day -> Days(
                        when (amount) {
                            is Period -> amount.toTotalMonths() * 30 + amount.days
                            is Duration -> amount.toDays()
                            else -> return null
                        },
                    )
                    else -> Exact(
                        when (amount) {
                            is Duration -> amount
                            is Period -> Duration.ofDays(amount.toTotalMonths() * 30 + amount.days)
                            else -> return null
                        },
                    )
                }
                return when (span) {
                    is Days -> span.takeIf { it.days >= 1 }
                    is Exact -> span.takeIf { !it.length.isNegative }
                }
            }

            /** Null when the end is unusable (before the start, or a different kind). */
            fun between(start: Point, end: Point): Span? = when {
                start is Point.Day && end is Point.Day ->
                    Days(end.date.toEpochDay() - start.date.toEpochDay()).takeIf { it.days >= 1 }
                start is Point.At && end is Point.At ->
                    Exact(Duration.between(start.instant, end.instant)).takeIf { !it.length.isNegative }
                start is Point.Wall && end is Point.Wall ->
                    Exact(Duration.between(start.time, end.time)).takeIf { !it.length.isNegative }
                else -> null
            }

            /** RFC 5545: a DATE start with no end lasts one day; a DATE-TIME start, zero. */
            fun defaultFor(start: Point): Span = if (start is Point.Day) Days(1) else Exact(Duration.ZERO)
        }
    }
}
