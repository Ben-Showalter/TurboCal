package com.turbocal.core.sync

import com.turbocal.core.ics.ExpansionWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.ZoneId

class FeedFetcherTest {
    private val url = "https://example.invalid/private-secret-token/basic.ics"
    private val window = ExpansionWindow.around(LocalDate.of(2026, 10, 27), ZoneId.of("America/New_York"))
    private val ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//t//EN
        BEGIN:VEVENT
        UID:a@test
        DTSTAMP:20261001T000000Z
        DTSTART:20261105T150000Z
        DTEND:20261105T160000Z
        SUMMARY:Hello
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n")

    private class Recorder(val respond: (Map<String, String>) -> HttpResponse) : HttpTransport {
        var headers: Map<String, String> = emptyMap()
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            this.headers = headers
            return respond(headers)
        }
    }

    @Test fun `200 parses and returns new validators`() {
        val t = Recorder { HttpResponse(200, mapOf("etag" to "\"v2\"", "last-modified" to "Tue, 27 Oct 2026 10:00:00 GMT"), ics.byteInputStream()) }
        val r = FeedFetcher(t).fetch(url, null, window) as FetchResult.Updated
        assertEquals(listOf("Hello"), r.instances.map { it.title })
        assertEquals(Validators("\"v2\"", "Tue, 27 Oct 2026 10:00:00 GMT"), r.validators)
        assertFalse(t.headers.containsKey("If-None-Match"))
    }

    @Test fun `sends conditional headers and handles 304`() {
        val t = Recorder { HttpResponse(304, emptyMap(), null) }
        val r = FeedFetcher(t).fetch(url, Validators("\"v1\"", "Mon, 26 Oct 2026 10:00:00 GMT"), window)
        assertEquals(FetchResult.NotModified, r)
        assertEquals("\"v1\"", t.headers["If-None-Match"])
        assertEquals("Mon, 26 Oct 2026 10:00:00 GMT", t.headers["If-Modified-Since"])
    }

    @Test fun `errors become plain text that never contains the url`() {
        val cases = listOf(
            Recorder { HttpResponse(404, emptyMap(), null) } to "Feed not found (404) - check the link",
            Recorder { HttpResponse(503, emptyMap(), null) } to "Server error (503) - will retry",
            Recorder { HttpResponse(403, emptyMap(), null) } to "Access denied (403) - the link may have been reset",
            Recorder { throw UnknownHostException(url) } to "No connection",
            Recorder { throw SocketTimeoutException(url) } to "Timed out - will retry",
            Recorder { HttpResponse(200, emptyMap(), "<html><body>login</body></html>".byteInputStream()) } to
                "Got a web page, not a calendar - check the link",
            Recorder { HttpResponse(200, emptyMap(), "garbage".byteInputStream()) } to "Not an iCalendar feed - check the link",
            Recorder { HttpResponse(200, emptyMap(), ics.take(120).byteInputStream()) } to "Not an iCalendar feed - check the link",
        )
        for ((t, expected) in cases) {
            val r = FeedFetcher(t).fetch(url, null, window)
            assertTrue("$r", r is FetchResult.Failed)
            val msg = (r as FetchResult.Failed).message
            assertEquals(expected, msg)
            assertFalse(msg.contains("secret"))
        }
    }

    @Test fun `manual sync throttle`() {
        val th = ManualSyncThrottle()
        assertEquals(null, th.minutesToWait(null, 1_000_000))
        assertEquals(2, th.minutesToWait(1_000_000, 1_000_000 + 1_000))
        assertEquals(1, th.minutesToWait(1_000_000, 1_000_000 + 90_000))
        assertEquals(null, th.minutesToWait(1_000_000, 1_000_000 + 120_000))
        assertEquals("Already up to date - try again in about 2 min", th.message(2))
    }
}
