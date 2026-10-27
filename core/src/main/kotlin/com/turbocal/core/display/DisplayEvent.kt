package com.turbocal.core.display

import com.turbocal.core.model.EventTime

/** A stored instance joined with its calendar, as every screen sees it. */
data class DisplayEvent(
    val id: Long,
    val feedId: Long,
    val feedName: String,
    val color: Int,
    val title: String,
    val location: String,
    val description: String,
    val time: EventTime,
)
