package com.turbocal.app.sync

import com.turbocal.app.data.EventEntity
import com.turbocal.app.data.FeedEntity
import com.turbocal.app.data.LocalEventEntity
import com.turbocal.app.data.TurboCalDao
import com.turbocal.core.feeds.FeedColors
import com.turbocal.core.ics.ExpansionWindow
import com.turbocal.core.local.LocalCalendar
import com.turbocal.core.local.LocalEvent
import com.turbocal.core.local.LocalImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The "On this phone" calendar (AGENTS.md §11): the only writable calendar.
 * Every change is followed by a re-expansion through ical4j (core
 * LocalCalendar), so its events reach the screens exactly like a feed's.
 */
class LocalCalendarRunner(
    private val dao: TurboCalDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** The phone calendar, created on first use. */
    suspend fun ensureFeed(): FeedEntity = withContext(Dispatchers.IO) {
        dao.localFeed() ?: run {
            val id = dao.insertFeed(
                FeedEntity(name = DEFAULT_NAME, url = "", color = FeedColors.PALETTE[0], source = FeedEntity.SOURCE_LOCAL),
            )
            dao.feed(id)!!
        }
    }

    suspend fun event(uid: String): LocalEvent? = withContext(Dispatchers.IO) { dao.localEvent(uid)?.toModel() }

    /** Inserts or replaces [event] (matched by uid), then re-expands. */
    suspend fun save(event: LocalEvent) = withContext(Dispatchers.IO) {
        val feed = ensureFeed()
        upsert(feed.id, event)
        reexpand(feed.id)
    }

    suspend fun delete(uid: String) = withContext(Dispatchers.IO) {
        val feedId = dao.localEvent(uid)?.feedId ?: return@withContext
        dao.deleteLocalEvent(uid)
        reexpand(feedId)
    }

    /** "Delete this one": excludes one occurrence of a repeating event. */
    suspend fun deleteOccurrence(uid: String, recurrenceKey: String) = withContext(Dispatchers.IO) {
        val start = LocalCalendar.occurrenceStart(recurrenceKey)
        val current = dao.localEvent(uid)?.toModel() ?: return@withContext
        if (start == null || current.rrule == null) {
            delete(uid)
            return@withContext
        }
        save(current.copy(exdates = (current.exdates + start).distinct()))
    }

    /** Rebuilds the stored instances of the phone calendar for today's window. */
    suspend fun reexpand(feedId: Long) = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val window = ExpansionWindow.around(LocalDate.now(zone), zone)
        val instances = LocalCalendar.expand(dao.localEvents(feedId).map { it.toModel() }, window)
        val rows = instances.map {
            EventEntity(
                feedId = feedId, uid = it.uid, recurrenceKey = it.recurrenceKey, title = it.title,
                location = it.location, description = it.description,
                kind = it.time.kind.code, startKey = it.time.startKey, endKey = it.time.endKey,
            )
        }
        dao.replaceEvents(feedId, rows, null, null, clock())
    }

    /** Writes the whole phone calendar to [file] as .ics; returns the event count. */
    suspend fun exportTo(file: File): Int = withContext(Dispatchers.IO) {
        val feed = dao.localFeed() ?: return@withContext 0
        val events = dao.localEvents(feed.id).map { it.toModel() }
        file.parentFile?.mkdirs()
        file.writeText(LocalCalendar.toIcs(events), Charsets.UTF_8)
        events.size
    }

    /** Adds (or, by uid, replaces) the events in an .ics file. */
    suspend fun importFrom(file: File): LocalImport = withContext(Dispatchers.IO) {
        val result = file.inputStream().use { LocalCalendar.fromIcs(it, ZoneId.systemDefault()) }
        val feed = ensureFeed()
        for (e in result.events) upsert(feed.id, e)
        reexpand(feed.id)
        result
    }

    private suspend fun upsert(feedId: Long, e: LocalEvent) {
        val row = e.toEntity(feedId)
        val existing = dao.localEvent(e.uid)
        if (existing == null) dao.insertLocalEvent(row) else dao.updateLocalEvent(row.copy(id = existing.id, feedId = existing.feedId))
    }

    private fun LocalEventEntity.toModel() = LocalEvent(
        uid = uid, title = title, location = location, description = description, allDay = allDay,
        start = LocalDateTime.parse(start), end = LocalDateTime.parse(end),
        rrule = rrule?.takeIf { it.isNotBlank() },
        exdates = exdates.split(',').filter { it.isNotBlank() }.map { LocalDateTime.parse(it) },
    )

    private fun LocalEvent.toEntity(feedId: Long) = LocalEventEntity(
        feedId = feedId, uid = uid, title = title, location = location, description = description, allDay = allDay,
        start = start.toString(), end = end.toString(), rrule = rrule, exdates = exdates.joinToString(",") { it.toString() },
    )

    companion object {
        const val DEFAULT_NAME = "On this phone"
    }
}
