package com.turbocal.core.ics

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.data.CalendarParserFactory
import net.fortuna.ical4j.data.ParserException
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.TimeZone
import net.fortuna.ical4j.model.TimeZoneRegistry
import net.fortuna.ical4j.model.TimeZoneRegistryImpl
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/** The body was not an iCalendar file (an HTML login page, an error page, garbage). */
class NotICalendarException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Turns a feed body into an ical4j [Calendar]. All parsing is ical4j's
 * (AGENTS.md §1); this only configures it and recognises non-calendar bodies.
 */
object IcsParser {

    fun parse(input: InputStream): Calendar {
        Ical4jConfig.apply()
        val stream = BufferedInputStream(input)
        if (looksLikeHtml(stream)) throw NotICalendarException("Got a web page, not a calendar")
        val builder = CalendarBuilder(CalendarParserFactory.getInstance().get(), FallbackTimeZoneRegistry())
        return try {
            builder.build(stream)
        } catch (e: ParserException) {
            throw NotICalendarException("Not an iCalendar feed", e)
        }
    }

    private fun looksLikeHtml(stream: BufferedInputStream): Boolean {
        stream.mark(PEEK)
        val head = ByteArray(PEEK)
        var n = 0
        while (n < PEEK) {
            val r = stream.read(head, n, PEEK - n)
            if (r < 0) break
            n += r
        }
        stream.reset()
        val text = String(head, 0, n, Charsets.UTF_8).trimStart('﻿', ' ', '\t', '\r', '\n')
        if (text.isEmpty()) throw NotICalendarException("Empty feed")
        return text.startsWith("<")
    }

    private const val PEEK = 256
}

/**
 * ical4j's registry, plus a fallback for Outlook's Windows zone names
 * ("Pacific Standard Time") when the feed didn't embed a VTIMEZONE for them.
 * Embedded VTIMEZONEs are registered by the builder and win, because the
 * lookup by the exact TZID succeeds first.
 */
internal class FallbackTimeZoneRegistry(
    private val delegate: TimeZoneRegistry = TimeZoneRegistryImpl(),
) : TimeZoneRegistry by delegate {
    override fun getTimeZone(id: String): TimeZone? {
        delegate.getTimeZone(id)?.let { return it }
        val iana = WindowsZones.toIana(id) ?: return null
        return delegate.getTimeZone(iana)
    }
}
