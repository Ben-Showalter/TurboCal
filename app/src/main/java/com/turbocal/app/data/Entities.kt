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
