package com.turbocal.core.ics

import net.fortuna.ical4j.util.CompatibilityHints

/**
 * The settings ical4j needs on Android (AGENTS.md §4). Call [apply] before the
 * first parse; it is idempotent. ical4j reads some of these as system
 * properties when its classes load, so they are set both ways.
 */
object Ical4jConfig {
    @Volatile private var applied = false

    fun apply() {
        if (applied) return
        synchronized(this) {
            if (applied) return
            // The default JCache-backed zone cache isn't available on Android.
            System.setProperty("net.fortuna.ical4j.timezone.cache.impl", "net.fortuna.ical4j.util.MapTimeZoneCache")
            // Never fetch zone definitions over the network.
            System.setProperty("net.fortuna.ical4j.timezone.update.enabled", "false")
            System.setProperty("ical4j.unfolding.relaxed", "true")
            System.setProperty("ical4j.parsing.relaxed", "true")
            System.setProperty("ical4j.validation.relaxed", "true")
            System.setProperty("ical4j.compatibility.outlook", "true")
            CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_UNFOLDING, true)
            CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_PARSING, true)
            CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_VALIDATION, true)
            CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_OUTLOOK_COMPATIBILITY, true)
            applied = true
        }
    }
}
