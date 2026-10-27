package com.turbocal.core.model

/**
 * One occurrence of an event, after recurrence expansion. This is what sync
 * stores and what every screen reads.
 *
 * [recurrenceKey] tells instances of one series apart; it is empty for a
 * non-recurring event.
 */
data class EventInstance(
    val uid: String,
    val recurrenceKey: String,
    val title: String,
    val location: String,
    val description: String,
    val time: EventTime,
)
