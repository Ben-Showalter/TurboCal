package com.turbocal.core.notify

import com.turbocal.core.display.DisplayEvent
import com.turbocal.core.display.sortForDay
import com.turbocal.core.model.EventTime
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** The owner's notification settings (Settings > Notifications). */
data class ReminderSettings(
    val enabled: Boolean = true,
    /** Minutes before a timed event starts; 0 = at the start. All-day events ignore it. */
    val leadMinutes: Int = 5,
    /** When all-day events notify, on their first day. */
    val allDayTime: LocalTime = LocalTime.of(8, 0),
)

/** Events that notify at the same instant: one notification for all of them. */
data class ReminderGroup(val at: Instant, val events: List<DisplayEvent>) {
    val allDay: Boolean get() = events.all { it.time is EventTime.AllDay }
}

/**
 * When each event notifies, and which notify together (AGENTS.md §8).
 * Pure, so the rules are unit-tested; the app only schedules one alarm for
 * the next group and posts it.
 */
object Reminders {
    val LEAD_CHOICES = listOf(0, 5, 10, 15, 30)

    fun notifyAt(e: DisplayEvent, s: ReminderSettings, zone: ZoneId): Instant = when (val t = e.time) {
        // All-day events: their first day at the chosen time; no lead.
        is EventTime.AllDay -> t.start.atTime(s.allDayTime).atZone(zone).toInstant()
        else -> t.startInstant(zone).minusSeconds(s.leadMinutes * 60L)
    }

    /**
     * The earliest notification strictly after [after], with every event that
     * notifies at that same instant ("wrap them together"), in Day-view order.
     * Null when notifications are off or nothing is due.
     */
    fun nextGroup(events: List<DisplayEvent>, s: ReminderSettings, zone: ZoneId, after: Instant): ReminderGroup? {
        if (!s.enabled) return null
        val due = events.map { it to notifyAt(it, s, zone) }.filter { it.second.isAfter(after) }
        val at = due.minOfOrNull { it.second } ?: return null
        val group = due.filter { it.second == at }.map { it.first }.distinctBy { it.id }
        return ReminderGroup(at, sortForDay(group, zone))
    }

    /** The group that notifies exactly at [at] (re-read at fire time, so edits since scheduling count). */
    fun groupAt(events: List<DisplayEvent>, s: ReminderSettings, zone: ZoneId, at: Instant): ReminderGroup? {
        if (!s.enabled) return null
        val group = events.filter { notifyAt(it, s, zone) == at }.distinctBy { it.id }
        return if (group.isEmpty()) null else ReminderGroup(at, sortForDay(group, zone))
    }
}
