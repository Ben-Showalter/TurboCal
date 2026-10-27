package com.turbocal.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TurboCalDao {

    @Query("SELECT * FROM feeds ORDER BY name COLLATE NOCASE, id")
    abstract fun feeds(): Flow<List<FeedEntity>>

    @Query("SELECT * FROM feeds ORDER BY name COLLATE NOCASE, id")
    abstract suspend fun allFeeds(): List<FeedEntity>

    @Query("SELECT * FROM feeds WHERE id = :id")
    abstract suspend fun feed(id: Long): FeedEntity?

    @Insert
    abstract suspend fun insertFeed(feed: FeedEntity): Long

    @Update
    abstract suspend fun updateFeed(feed: FeedEntity)

    @Query("DELETE FROM feeds WHERE id = :id")
    abstract suspend fun deleteFeed(id: Long)

    @Query("UPDATE feeds SET enabled = :enabled WHERE id = :id")
    abstract suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE feeds SET lastSuccessAt = :at, lastAttemptAt = :at, lastError = NULL WHERE id = :id")
    abstract suspend fun markNotModified(id: Long, at: Long)

    @Query("UPDATE feeds SET lastAttemptAt = :at, lastError = :error WHERE id = :id")
    abstract suspend fun markFailed(id: Long, error: String, at: Long)

    @Query(
        "UPDATE feeds SET etag = :etag, lastModified = :lastModified, lastParsedAt = :at, " +
            "lastSuccessAt = :at, lastAttemptAt = :at, lastError = NULL WHERE id = :id",
    )
    abstract suspend fun markUpdated(id: Long, etag: String?, lastModified: String?, at: Long)

    @Query("DELETE FROM events WHERE feedId = :feedId")
    abstract suspend fun deleteEvents(feedId: Long)

    @Insert
    abstract suspend fun insertEvents(events: List<EventEntity>)

    /** Swap one feed's events in a single transaction, only after a full parse (AGENTS.md §1 rule 4). */
    @Transaction
    open suspend fun replaceEvents(feedId: Long, events: List<EventEntity>, etag: String?, lastModified: String?, at: Long) {
        deleteEvents(feedId)
        if (events.isNotEmpty()) insertEvents(events)
        markUpdated(feedId, etag, lastModified, at)
    }

    /**
     * Events whose keys overlap [fromKey, toKey). Callers widen the range by
     * EventTime.KEY_SLACK_MILLIS and then filter exactly with EventTime.overlaps.
     */
    @Query(
        "SELECT e.id, e.feedId, f.name AS feedName, f.color AS color, e.title, e.location, e.description, " +
            "e.kind, e.startKey, e.endKey FROM events e INNER JOIN feeds f ON f.id = e.feedId " +
            "WHERE e.endKey >= :fromKey AND e.startKey < :toKey AND (f.enabled = 1 OR :showDisabled = 1) " +
            "ORDER BY e.startKey, e.id",
    )
    abstract fun eventsBetween(fromKey: Long, toKey: Long, showDisabled: Boolean): Flow<List<EventWithFeed>>

    @Query(
        "SELECT e.id, e.feedId, f.name AS feedName, f.color AS color, e.title, e.location, e.description, " +
            "e.kind, e.startKey, e.endKey FROM events e INNER JOIN feeds f ON f.id = e.feedId WHERE e.id = :id",
    )
    abstract suspend fun event(id: Long): EventWithFeed?
}
