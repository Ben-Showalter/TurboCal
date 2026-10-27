package com.turbocal.core

import org.junit.rules.ExternalResource
import java.util.TimeZone

/** Pins the JVM default zone (the "phone's zone") for one test, then restores it. */
class TimeZoneRule(private val id: String) : ExternalResource() {
    private var saved: TimeZone? = null

    override fun before() {
        saved = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(id))
    }

    override fun after() {
        TimeZone.setDefault(saved)
    }
}
