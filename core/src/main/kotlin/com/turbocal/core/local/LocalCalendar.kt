package com.turbocal.core.local

import com.turbocal.core.ics.ExpansionWindow
import com.turbocal.core.ics.Expander
import com.turbocal.core.ics.Ical4jConfig
import com.turbocal.core.ics.IcsParser
import com.turbocal.core.model.EventInstance
import net.fortuna.ical4j.data.CalendarOutputter
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Date
import net.fortuna.ical4j.model.DateList
import net.fortuna.ical4j.model.DateTime
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.Recur
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.parameter.Value
import net.fortuna.ical4j.model.property.CalScale
import net.fortuna.ical4j.model.property.Description
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.ExDate
import net.fortuna.ical4j.model.property.Location
import net.fortuna.ical4j.model.property.ProdId
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.Summary
import net.fortuna.ical4j.model.property.Uid
import net.fortuna.ical4j.model.property.Version
import java.io.InputStream
import java.io.StringWriter
import java.text.ParseException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One event in the "On this phone" calendar. Timed events are floating (a
 * wall-clock time in the phone's zone). For all-day events [start] and [end]
 * are midnights and [end] is exclusive, as in iCalendar.
 */
data class LocalEvent(
    val uid: String,
    val title: String,
    val location: String,
    val description: String,
    val allDay: Boolean,
    val start: LocalDateTime,
    val end: LocalDateTime,
    /** An RRULE value ("FREQ=WEEKLY"), or null. Any rule ical4j understands is kept as is. */
    val rrule: String?,
    /** Original starts of deleted occurrences. */
    val exdates: List<LocalDateTime> = emptyList(),
)

/** The simple repeat choices the editor offers. Any other RRULE is "custom" and kept untouched. */
enum class RepeatChoice(val label: String, val rrule: String?) {
    NONE("Doesn't repeat", null),
    DAILY("Every day", "FREQ=DAILY"),
    WEEKLY("Every week", "FREQ=WEEKLY"),
    MONTHLY("Every month", "FREQ=MONTHLY"),
    YEARLY("Every year", "FREQ=YEARLY"),
    ;

    companion object {
        /** Null means a custom rule the editor can't show as one of these. */
        fun of(rrule: String?): RepeatChoice? = values().firstOrNull { it.rrule.equals(rrule?.trim(), ignoreCase = true) }
    }
}

data class LocalImport(val events: List<LocalEvent>, val skipped: Int)

/**
 * Builds the local calendar as real ical4j components, so expansion, export
 * and import all go through ical4j exactly like a subscribed feed (AGENTS.md §1).
 */
object LocalCalendar {
    const val EXPORT_FILE_NAME = "turbocal_local.ics"

    private val WALL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun toCalendar(events: List<LocalEvent>): Calendar {
        Ical4jConfig.apply()
        val cal = Calendar()
        cal.properties.add(ProdId("-//TurboCal//On this phone//EN"))
        cal.properties.add(Version.VERSION_2_0)
        cal.properties.add(CalScale.GREGORIAN)
        for (e in events) cal.components.add(toVEvent(e))
        return cal
    }

    fun expand(events: List<LocalEvent>, window: ExpansionWindow): List<EventInstance> =
        Expander.expand(toCalendar(events), window)

    /** The export file's text. */
    fun toIcs(events: List<LocalEvent>): String {
        val out = StringWriter()
        CalendarOutputter(false).output(toCalendar(events), out)
        return out.toString()
    }

    /**
     * Reads any .ics into local events. Zoned and UTC times become wall times
     * in [zone]. Changed single occurrences (RECURRENCE-ID) can't be kept and
     * are counted in [LocalImport.skipped], as are cancelled events.
     */
    fun fromIcs(input: InputStream, zone: ZoneId): LocalImport {
        val cal = IcsParser.parse(input)
        val out = ArrayList<LocalEvent>()
        var skipped = 0
        for (v in cal.getComponents<VEvent>(Component.VEVENT)) {
            val start = v.startDate?.date
            if (start == null || v.recurrenceId != null || v.status?.value.equals("CANCELLED", true)) {
                skipped++
                continue
            }
            val allDay = start !is DateTime
            val s = wall(start, zone)
            val endDate = v.getEndDate(true)?.date
            val e = endDate?.let { wall(it, zone) }?.takeIf { it.isAfter(s) || (!allDay && it == s) }
                ?: if (allDay) s.plusDays(1) else s.plusHours(1)
            val rrule = v.getProperties<RRule>(Property.RRULE).firstOrNull()?.recur?.toString()
            val ex = v.getProperties<ExDate>(Property.EXDATE).flatMap { p -> p.dates.map { wall(it, zone) } }
            out += LocalEvent(
                uid = v.uid?.value?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
                title = v.summary?.value.orEmpty().trim(),
                location = v.location?.value.orEmpty().trim(),
                description = v.description?.value.orEmpty().trim(),
                allDay = allDay,
                start = s,
                end = e,
                rrule = rrule,
                exdates = ex,
            )
        }
        return LocalImport(out, skipped)
    }

    /**
     * The original start of one occurrence, from an instance's recurrenceKey
     * ("D:2026-11-05" or "F:2026-11-05T09:30"), for "Delete this one" (an
     * EXDATE). Null for a non-recurring event or a key from a zoned feed.
     */
    fun occurrenceStart(recurrenceKey: String): LocalDateTime? = runCatching {
        when {
            recurrenceKey.startsWith("D:") -> LocalDate.parse(recurrenceKey.substring(2)).atStartOfDay()
            recurrenceKey.startsWith("F:") -> LocalDateTime.parse(recurrenceKey.substring(2))
            else -> null
        }
    }.getOrNull()

    /** True when ical4j accepts [rrule]. */
    fun isValidRule(rrule: String): Boolean = try {
        Recur(rrule)
        true
    } catch (e: ParseException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun toVEvent(e: LocalEvent): VEvent {
        val v = VEvent()
        v.properties.add(Uid(e.uid))
        v.properties.add(DtStart(icalDate(e.start, e.allDay)))
        v.properties.add(DtEnd(icalDate(e.end, e.allDay)))
        v.properties.add(Summary(e.title))
        if (e.location.isNotBlank()) v.properties.add(Location(e.location))
        if (e.description.isNotBlank()) v.properties.add(Description(e.description))
        e.rrule?.takeIf { it.isNotBlank() }?.let { v.properties.add(RRule(Recur(it))) }
        if (e.exdates.isNotEmpty()) {
            val list = DateList(if (e.allDay) Value.DATE else Value.DATE_TIME)
            for (x in e.exdates) list.add(icalDate(x, e.allDay))
            v.properties.add(ExDate(list))
        }
        return v
    }

    /** A DATE for all-day events, otherwise a floating DATE-TIME (no zone). */
    private fun icalDate(t: LocalDateTime, allDay: Boolean): Date =
        if (allDay) Date(t.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE)) else DateTime(t.format(WALL))

    private fun wall(d: Date, zone: ZoneId): LocalDateTime = when {
        d !is DateTime -> LocalDate.parse(d.toString().take(8), DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay()
        d.isUtc || d.timeZone != null -> LocalDateTime.ofInstant(Instant.ofEpochMilli(d.time), zone)
        else -> LocalDateTime.parse(d.toString().take(15), WALL)
    }
}
