package com.turbocal.app.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.turbocal.app.data.FeedEntity
import com.turbocal.app.data.Prefs
import com.turbocal.app.data.TurboCalDatabase
import com.turbocal.core.sync.FeedFetcher
import com.turbocal.core.sync.HttpResponse
import com.turbocal.core.sync.HttpTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AGENTS.md §1 rule 4: a failed fetch keeps the last good events and records the error. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SyncRunnerTest {
    private lateinit var db: TurboCalDatabase
    private var respond: () -> HttpResponse = { HttpResponse(500, emptyMap(), null) }
    private val transport = HttpTransport { _, _ -> respond() }

    /** A three-day series starting two days from now, so it is always inside the sync window. */
    private val ics get() = template.replace("YYYYMMDD", java.time.LocalDate.now().plusDays(2).toString().replace("-", ""))

    private val template = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//t//EN",
        "BEGIN:VEVENT", "UID:a@test", "DTSTAMP:20261001T000000Z",
        "DTSTART:YYYYMMDDT150000Z", "DTEND:YYYYMMDDT160000Z", "RRULE:FREQ=DAILY;COUNT=3", "SUMMARY:Hello",
        "END:VEVENT", "END:VCALENDAR",
    ).joinToString("\r\n")

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, TurboCalDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun `success stores events, failure keeps them, 304 clears the error`() = runTest {
        val dao = db.dao()
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        var now = System.currentTimeMillis()
        val runner = SyncRunner(dao, FeedFetcher(transport), prefs) { now }
        val id = dao.insertFeed(FeedEntity(name = "A", url = "https://example.invalid/a.ics", color = 1))

        respond = { HttpResponse(200, mapOf("etag" to "\"v1\""), ics.byteInputStream()) }
        runner.run()
        var feed = dao.feed(id)!!
        assertNull(feed.lastError)
        assertEquals("\"v1\"", feed.etag)
        val countAfterSuccess = dao.eventsBetween(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2, true).first().size
        assertEquals(3, countAfterSuccess)

        now += 60_000
        respond = { HttpResponse(503, emptyMap(), null) }
        runner.run()
        feed = dao.feed(id)!!
        assertEquals("Server error (503) - will retry", feed.lastError)
        assertEquals(countAfterSuccess, dao.eventsBetween(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2, true).first().size)

        now += 60_000
        respond = { HttpResponse(304, emptyMap(), null) }
        runner.run()
        feed = dao.feed(id)!!
        assertNull(feed.lastError)
        assertEquals(now, feed.lastSuccessAt)
    }

    @Test fun `hidden calendars still sync`() = runTest {
        val dao = db.dao()
        val runner = SyncRunner(dao, FeedFetcher(transport), Prefs(ApplicationProvider.getApplicationContext()))
        val id = dao.insertFeed(FeedEntity(name = "H", url = "https://example.invalid/h.ics", color = 1, enabled = false))
        respond = { HttpResponse(200, emptyMap(), ics.byteInputStream()) }
        runner.run()
        assertEquals(3, dao.eventsBetween(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2, true).first().size)
        assertEquals(0, dao.eventsBetween(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2, false).first().size) // hidden from views
        assertNull(dao.feed(id)!!.lastError)
    }

    @Test fun `stored instances survive a garbage body`() = runTest {
        val dao = db.dao()
        val runner = SyncRunner(dao, FeedFetcher(transport), Prefs(ApplicationProvider.getApplicationContext()))
        val id = dao.insertFeed(FeedEntity(name = "B", url = "https://example.invalid/b.ics", color = 1))
        respond = { HttpResponse(200, emptyMap(), ics.byteInputStream()) }
        runner.run()
        val all = { dao.eventsBetween(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2, true) }
        assertEquals(3, all().first().size)

        respond = { HttpResponse(200, emptyMap(), "<html>login</html>".byteInputStream()) }
        runner.run()
        assertEquals(3, all().first().size)
        assertEquals("Got a web page, not a calendar - check the link", dao.feed(id)!!.lastError)
    }
}
