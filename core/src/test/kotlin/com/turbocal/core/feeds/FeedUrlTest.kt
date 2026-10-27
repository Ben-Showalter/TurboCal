package com.turbocal.core.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FeedUrlTest {
    @Test fun `webcal becomes https`() {
        assertEquals("https://example.invalid/cal/basic.ics", FeedUrl.normalize("webcal://example.invalid/cal/basic.ics"))
        assertEquals("https://example.invalid/a.ics", FeedUrl.normalize("  WEBCALS://example.invalid/a.ics \n"))
    }

    @Test fun `http and https pass, anything else is rejected`() {
        assertEquals("https://example.invalid/a.ics?x=1", FeedUrl.normalize("https://example.invalid/a.ics?x=1"))
        assertEquals("http://example.invalid/a.ics", FeedUrl.normalize("http://example.invalid/a.ics"))
        assertNull(FeedUrl.normalize("ftp://example.invalid/a.ics"))
        assertNull(FeedUrl.normalize("example.invalid/a.ics"))
        assertNull(FeedUrl.normalize("https:///nohost"))
        assertNull(FeedUrl.normalize("https://exa mple.invalid/a.ics"))
        assertNull(FeedUrl.normalize(""))
    }

    @Test fun `redacted keeps only scheme and host`() {
        val secret = "https://calendar.example.invalid/calendar/ical/abc%40group/private-0123456789abcdef/basic.ics"
        val r = FeedUrl.redacted(secret)
        assertEquals("https://calendar.example.invalid/…", r)
        assertFalse(r.contains("private"))
        assertEquals("(feed)", FeedUrl.redacted("not a url"))
    }
}
