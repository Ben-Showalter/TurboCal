package com.turbocal.app.sync

import android.util.Log
import com.turbocal.app.data.EventEntity
import com.turbocal.app.data.FeedEntity
import com.turbocal.app.data.Prefs
import com.turbocal.app.data.TurboCalDao
import com.turbocal.core.feeds.FeedUrl
import com.turbocal.core.ics.ExpansionWindow
import com.turbocal.core.model.EventInstance
import com.turbocal.core.sync.FeedFetcher
import com.turbocal.core.sync.FetchResult
import com.turbocal.core.sync.Validators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Fetches each enabled feed and stores the result (AGENTS.md §5). Every feed
 * fails independently, and a failure only records its message: the last good
 * events stay.
 */
class SyncRunner(
    private val dao: TurboCalDao,
    private val fetcher: FeedFetcher,
    private val prefs: Prefs,
    private val local: LocalCalendarRunner = LocalCalendarRunner(dao),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Syncs [onlyFeedId], or every enabled feed when null. */
    suspend fun run(onlyFeedId: Long? = null) = withContext(Dispatchers.IO) {
        // Hidden calendars (enabled = false) still sync, so showing one again is instant (owner, round 6).
        val feeds = dao.allFeeds().filter { onlyFeedId == null || it.id == onlyFeedId }
        val zone = ZoneId.systemDefault()
        val window = ExpansionWindow.around(LocalDate.now(zone), zone)
        var allOk = true
        for (feed in feeds) {
            // The phone calendar has nothing to fetch; re-expanding moves its window on.
            if (feed.isLocal) local.reexpand(feed.id) else if (!syncOne(feed, window)) allOk = false
        }
        if (onlyFeedId == null && allOk && feeds.any { !it.isLocal }) prefs.lastCleanSyncAt = clock()
    }

    private suspend fun syncOne(feed: FeedEntity, window: ExpansionWindow): Boolean {
        val now = clock()
        // The window rolls forward every day, so a 304 on a day-old parse would
        // leave the far end unexpanded: re-fetch in full once a day.
        val fresh = feed.lastParsedAt != null && now - feed.lastParsedAt in 0 until DAY_MILLIS
        val validators = if (fresh) Validators(feed.etag, feed.lastModified) else null
        return when (val r = fetcher.fetch(feed.url, validators, window)) {
            is FetchResult.NotModified -> {
                dao.markNotModified(feed.id, now)
                true
            }
            is FetchResult.Updated -> {
                dao.replaceEvents(feed.id, r.instances.map { it.toEntity(feed.id) }, r.validators.etag, r.validators.lastModified, now)
                Log.i(TAG, "feed ${feed.id} (${FeedUrl.redacted(feed.url)}): ${r.instances.size} instances")
                true
            }
            is FetchResult.Failed -> {
                dao.markFailed(feed.id, r.message, now)
                Log.w(TAG, "feed ${feed.id} (${FeedUrl.redacted(feed.url)}) failed: ${r.message}")
                false
            }
        }
    }

    private fun EventInstance.toEntity(feedId: Long) = EventEntity(
        feedId = feedId,
        uid = uid,
        recurrenceKey = recurrenceKey,
        title = title,
        location = location,
        description = description,
        kind = time.kind.code,
        startKey = time.startKey,
        endKey = time.endKey,
    )

    companion object {
        const val TAG = "TurboCalSync"
        private const val DAY_MILLIS = 86_400_000L
    }
}
