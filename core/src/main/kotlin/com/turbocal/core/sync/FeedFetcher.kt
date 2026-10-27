package com.turbocal.core.sync

import com.turbocal.core.ics.ExpansionWindow
import com.turbocal.core.ics.FeedReader
import com.turbocal.core.ics.NotICalendarException
import com.turbocal.core.model.EventInstance
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** HTTP cache validators kept per feed (AGENTS.md §5). */
data class Validators(val etag: String?, val lastModified: String?)

sealed class FetchResult {
    /** 304: nothing changed; keep the stored events. */
    object NotModified : FetchResult()

    /** The whole feed parsed; replace this feed's events with [instances]. */
    data class Updated(val instances: List<EventInstance>, val validators: Validators) : FetchResult()

    /** Keep the last good events; show [message] in the feed's status line. */
    data class Failed(val message: String) : FetchResult()
}

/**
 * Fetches and parses one feed. It never throws: every failure becomes a
 * plain-language [FetchResult.Failed] that never contains the URL.
 */
class FeedFetcher(private val transport: HttpTransport) {

    fun fetch(url: String, validators: Validators?, window: ExpansionWindow): FetchResult {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "text/calendar, */*;q=0.5"
        validators?.etag?.let { headers["If-None-Match"] = it }
        validators?.lastModified?.let { headers["If-Modified-Since"] = it }
        return try {
            transport.get(url, headers).use { r ->
                when {
                    r.code == 304 -> FetchResult.NotModified
                    r.code in 200..299 -> {
                        val body = r.body ?: return FetchResult.Failed("Empty response")
                        // Parse fully before anything is stored (AGENTS.md §1 rule 4).
                        val instances = FeedReader.read(body, window)
                        FetchResult.Updated(instances, Validators(r.headers["etag"], r.headers["last-modified"]))
                    }
                    else -> FetchResult.Failed(httpMessage(r.code))
                }
            }
        } catch (e: NotICalendarException) {
            FetchResult.Failed(
                if (e.message?.contains("web page") == true) "Got a web page, not a calendar - check the link"
                else "Not an iCalendar feed - check the link",
            )
        } catch (e: Exception) {
            FetchResult.Failed(errorMessage(e))
        }
    }

    companion object {
        fun httpMessage(code: Int): String = when (code) {
            401, 403 -> "Access denied ($code) - the link may have been reset"
            404, 410 -> "Feed not found ($code) - check the link"
            429 -> "Server busy (429) - will retry"
            in 500..599 -> "Server error ($code) - will retry"
            else -> "Unexpected reply ($code)"
        }

        fun errorMessage(e: Throwable): String = when (e) {
            is UnknownHostException, is ConnectException -> "No connection"
            is SocketTimeoutException -> "Timed out - will retry"
            is SSLException -> "Secure connection failed"
            is InsecureRedirectException -> "Feed redirected to an insecure address"
            is TooManyRedirectsException -> "Too many redirects - check the link"
            is OutOfMemoryError -> "Feed too large"
            is IOException -> "Network error"
            else -> "Couldn't read the feed"
        }
    }
}
