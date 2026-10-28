package com.turbocal.core.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FeedImportTest {
    private val sample = """
        { "version": 1,
          "feeds": [
            { "name": "Family", "url": "webcal://example.invalid/family.ics", "color": "#64B5F6" },
            { "name": "Work", "url": "https://example.invalid/work.ics", "enabled": false },
            { "name": "Broken", "url": "not a url" },
            { "url": "https://example.invalid/noname.ics" },
            { "name": "Dupe", "url": "https://example.invalid/family.ics" }
          ] }
    """.trimIndent()

    @Test fun `parses, normalises and skips bad entries`() {
        val f = FeedImport.parse(sample)
        assertEquals(1, f.skipped)
        assertEquals(listOf("Family", "Work", "Calendar 4"), f.feeds.map { it.name })
        assertEquals("https://example.invalid/family.ics", f.feeds[0].url)
        assertEquals(0xFF64B5F6.toInt(), f.feeds[0].color)
        assertFalse(f.feeds[1].enabled)
        assertEquals(null, f.feeds[1].color)
    }

    @Test fun `bare array is accepted`() {
        assertEquals(1, FeedImport.parse("""[{"name":"A","url":"https://example.invalid/a.ics"}]""").feeds.size)
    }

    @Test(expected = ImportFormatException::class)
    fun `invalid json is a format error`() {
        FeedImport.parse("{ nope")
    }

    @Test(expected = ImportFormatException::class)
    fun `missing feeds list is a format error`() {
        FeedImport.parse("""{"version":1}""")
    }

    @Test fun `calendar colours darken on the light theme only`() {
        val teal = 0xFF4DB6AC.toInt()
        assertEquals(teal, FeedColors.forTheme(teal, light = false))
        val dark = FeedColors.forTheme(teal, light = true)
        assertEquals(0xFF, dark ushr 24)
        assertEquals((0x4D * 0.65f).toInt(), dark shr 16 and 0xFF)
        assertEquals((0xAC * 0.65f).toInt(), dark and 0xFF)
    }

    @Test fun `plan merges by url`() {
        val existing = listOf(ExistingFeed(7, "webcal://example.invalid/family.ics"))
        val plan = FeedImport.plan(existing, FeedImport.parse(sample))
        assertEquals(listOf(7L), plan.updates.map { it.first })
        assertEquals(listOf("Work", "Calendar 4"), plan.adds.map { it.name })
        assertEquals("Imported 2, updated 1, skipped 1 (bad URL)", plan.summary())
    }
}
