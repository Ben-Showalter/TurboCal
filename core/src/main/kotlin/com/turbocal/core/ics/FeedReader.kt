package com.turbocal.core.ics

import com.turbocal.core.model.EventInstance
import java.io.InputStream

/** Feed body → expanded instances. The one entry point sync uses. */
object FeedReader {
    fun read(input: InputStream, window: ExpansionWindow): List<EventInstance> =
        Expander.expand(IcsParser.parse(input), window)
}
