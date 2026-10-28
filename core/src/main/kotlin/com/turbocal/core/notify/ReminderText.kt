package com.turbocal.core.notify

import com.turbocal.core.display.Formats
import com.turbocal.core.model.EventTime
import java.time.ZoneId

/** The words of one notification, built in core so they are unit-tested. */
data class ReminderText(
    val title: String,
    val text: String,
    /** One line per event, for an inbox-style notification. */
    val lines: List<String>,
    /** The outer display's one line, without the time (the cover notifier adds it). */
    val cover: String,
)

object ReminderTexts {
    private const val NO_TITLE = "(No title)"

    fun build(group: ReminderGroup, s: ReminderSettings, zone: ZoneId, fmt: Formats): ReminderText {
        val events = group.events
        val titles = events.map { it.title.ifBlank { NO_TITLE } }
        val lead = if (!group.allDay && s.leadMinutes > 0) " in ${s.leadMinutes} min" else ""
        val lines = events.map { e ->
            val day = e.time.firstDay(zone)
            "${fmt.rowTime(e.time, day, zone)}  ${e.title.ifBlank { NO_TITLE }}"
        }
        if (events.size == 1) {
            val e = events[0]
            val whenText = if (e.time is EventTime.AllDay) Formats.ALL_DAY else fmt.time(e.time.startInstant(zone), zone)
            val text = if (e.location.isBlank()) whenText else "$whenText · ${e.location}"
            return ReminderText(titles[0] + lead, text, lines, titles[0] + lead)
        }
        val n = events.size
        val title = when {
            group.allDay -> "$n all-day events today"
            lead.isNotEmpty() -> "$n events$lead"
            else -> "$n events at ${fmt.time(events[0].time.startInstant(zone), zone)}"
        }
        val list = titles.joinToString(", ")
        return ReminderText(title, list, lines, "$n events: $list")
    }
}
