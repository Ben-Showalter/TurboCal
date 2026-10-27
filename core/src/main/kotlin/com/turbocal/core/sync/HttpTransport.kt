package com.turbocal.core.sync

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A GET response. Header names are lower-case. Close it to release the connection. */
class HttpResponse(
    val code: Int,
    val headers: Map<String, String>,
    val body: InputStream?,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        runCatching { body?.close() }
        onClose()
    }
}

/** The network seam: tests pass a fake, the app passes [UrlConnectionTransport]. */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

class TooManyRedirectsException : IOException("Too many redirects")
class InsecureRedirectException : IOException("Redirected from https to http")

/**
 * Plain [HttpURLConnection] (AGENTS.md §2). Follows up to [maxRedirects]
 * itself, because HttpURLConnection won't follow cross-protocol redirects,
 * and refuses an https → http downgrade.
 */
class UrlConnectionTransport(
    private val userAgent: String,
    private val timeoutMillis: Int = 20_000,
    private val maxRedirects: Int = 5,
) : HttpTransport {

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        var current = URL(url)
        repeat(maxRedirects + 1) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                for ((k, v) in headers) setRequestProperty(k, v)
            }
            val code = conn.responseCode
            if (code in 300..399 && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("Redirect without a location")
                val next = URL(current, location)
                if (current.protocol == "https" && next.protocol != "https") throw InsecureRedirectException()
                current = next
                return@repeat
            }
            val responseHeaders = conn.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.firstOrNull().orEmpty() }
            val body = if (code in 200..299) conn.inputStream else null
            return HttpResponse(code, responseHeaders, body) { conn.disconnect() }
        }
        throw TooManyRedirectsException()
    }
}
