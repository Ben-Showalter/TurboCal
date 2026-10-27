package com.turbocal.core.ics

import com.turbocal.core.model.EventInstance
import java.time.LocalDate
import java.time.ZoneId

object Fixtures {
    val TODAY: LocalDate = LocalDate.of(2026, 10, 27)

    fun read(name: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = TODAY): List<EventInstance> {
        val stream = Fixtures::class.java.getResourceAsStream("/ics/$name") ?: error("missing fixture $name")
        return stream.use { FeedReader.read(it, ExpansionWindow.around(today, zone)) }
    }
}
