package com.turbocal.app.data

import com.turbocal.core.display.DisplayEvent
import com.turbocal.core.feeds.ExistingFeed
import com.turbocal.core.feeds.FeedColors
import com.turbocal.core.feeds.ImportPlan
import com.turbocal.core.model.EventTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

/** What screens read and what Settings writes. Screens never touch the network (AGENTS.md §1). */
class CalendarRepository(private val dao: TurboCalDao, private val prefs: Prefs) {

    val feeds: Flow<List<FeedEntity>> = dao.feeds()

    /** Events showing on any day in [from, toExclusive) in [zone]; respects the `#` toggle. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun events(from: LocalDate, toExclusive: LocalDate, zone: ZoneId): Flow<List<DisplayEvent>> {
        val fromInstant = from.atStartOfDay(zone).toInstant()
        val toInstant = toExclusive.atStartOfDay(zone).toInstant()
        val fromKey = fromInstant.toEpochMilli() - EventTime.KEY_SLACK_MILLIS
        val toKey = toInstant.toEpochMilli() + EventTime.KEY_SLACK_MILLIS
        return prefs.showDisabled.flatMapLatest { show ->
            dao.eventsBetween(fromKey, toKey, show).map { rows ->
                rows.map { it.toDisplay() }.filter { it.time.overlaps(fromInstant, toInstant, zone) }
            }
        }
    }

    suspend fun event(id: Long): DisplayEvent? = dao.event(id)?.toDisplay()

    suspend fun eventRef(id: Long): EventRef? = dao.eventRef(id)

    fun localEventCount(feedId: Long): Flow<Int> = dao.localEventCount(feedId)

    suspend fun feed(id: Long): FeedEntity? = dao.feed(id)

    suspend fun addFeed(name: String, url: String, color: Int?, enabled: Boolean): Long {
        val count = dao.allFeeds().size
        return dao.insertFeed(FeedEntity(name = name, url = url, color = color ?: FeedColors.forIndex(count), enabled = enabled))
    }

    /** Saves edits. A changed URL drops the cache validators so the next sync fetches in full. */
    suspend fun saveFeed(id: Long, name: String, url: String, color: Int, enabled: Boolean) {
        val old = dao.feed(id) ?: return
        val urlChanged = old.url != url
        dao.updateFeed(
            old.copy(
                name = name, url = url, color = color, enabled = enabled,
                etag = if (urlChanged) null else old.etag,
                lastModified = if (urlChanged) null else old.lastModified,
                lastParsedAt = if (urlChanged) null else old.lastParsedAt,
                lastError = if (urlChanged) null else old.lastError,
            ),
        )
        if (urlChanged) dao.deleteEvents(id)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun deleteFeed(id: Long) = dao.deleteFeed(id)

    suspend fun existingForImport(): List<ExistingFeed> = dao.allFeeds().filter { !it.isLocal }.map { ExistingFeed(it.id, it.url) }

    /** Applies a planned import; returns the ids of new feeds so they can be synced at once. */
    suspend fun applyImport(plan: ImportPlan): List<Long> {
        for ((id, f) in plan.updates) {
            val old = dao.feed(id) ?: continue
            dao.updateFeed(old.copy(name = f.name, color = f.color ?: old.color, enabled = f.enabled))
        }
        return plan.adds.map { addFeed(it.name, it.url, it.color, it.enabled) }
    }

    private fun EventWithFeed.toDisplay() = DisplayEvent(
        id = id,
        feedId = feedId,
        feedName = feedName,
        color = color,
        title = title,
        location = location,
        description = description,
        time = EventTime.fromKeys(EventTime.Kind.of(kind), startKey, endKey),
    )
}
