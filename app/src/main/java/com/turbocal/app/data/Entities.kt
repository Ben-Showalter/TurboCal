package com.turbocal.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One subscribed feed. [url] is a secret (AGENTS.md §1): it lives only here,
 * in app-private storage, and is never logged.
 */
@Entity(tableName = "feeds")
data class FeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val color: Int,
    val enabled: Boolean = true,
    val etag: String? = null,
    val lastModified: String? = null,
    /** Last time the body was fully parsed; validators are ignored once it is a day old (see SyncRunner). */
    val lastParsedAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    /** Plain-language error from the last attempt; null after a success. Never contains the URL. */
    val lastError: String? = null,
    /** [SOURCE_ICS] (a subscribed link) or [SOURCE_LOCAL] (the editable "On this phone" calendar; url is ""). */
    @ColumnInfo(defaultValue = "0") val source: Int = SOURCE_ICS,
) {
    val isLocal: Boolean get() = source == SOURCE_LOCAL

    companion object {
        const val SOURCE_ICS = 0
        const val SOURCE_LOCAL = 1
    }
}

/**
 * One event of the "On this phone" calendar, as the user entered it. Expanded
 * into [EventEntity] rows through ical4j (core LocalCalendar), like a feed.
 * [start]/[end] are ISO local date-times (floating); for all-day events they
 * are midnights and [end] is exclusive. [exdates] is a comma list of ISO starts.
 */
@Entity(
    tableName = "local_events",
    foreignKeys = [
        ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feedId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("feedId"), Index(value = ["uid"], unique = true)],
)
data class LocalEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedId: Long,
    val uid: String,
    val title: String,
    val location: String,
    val description: String,
    val allDay: Boolean,
    val start: String,
    val end: String,
    val rrule: String?,
    val exdates: String,
)

/**
 * One expanded instance. Times use [com.turbocal.core.model.EventTime]'s
 * zone-independent keys, so rows never change when the phone's zone does.
 */
@Entity(
    tableName = "events",
    foreignKeys = [
        ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feedId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("feedId"), Index("startKey"), Index("endKey")],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedId: Long,
    val uid: String,
    val recurrenceKey: String,
    val title: String,
    val location: String,
    val description: String,
    /** [com.turbocal.core.model.EventTime.Kind] code. */
    val kind: Int,
    val startKey: Long,
    val endKey: Long,
)

/** What the detail screen needs to edit or delete a phone-calendar event. */
data class EventRef(
    val id: Long,
    val feedId: Long,
    val uid: String,
    val recurrenceKey: String,
    val source: Int,
)

/** An event joined with its feed, for display. */
data class EventWithFeed(
    val id: Long,
    val feedId: Long,
    @ColumnInfo(name = "feedName") val feedName: String,
    val color: Int,
    val title: String,
    val location: String,
    val description: String,
    val kind: Int,
    val startKey: Long,
    val endKey: Long,
)
