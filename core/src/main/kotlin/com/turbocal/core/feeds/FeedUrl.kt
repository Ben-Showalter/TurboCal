package com.turbocal.core.feeds

import java.net.URI

/**
 * Feed URLs are secrets (AGENTS.md §1). [normalize] is the only way a URL gets
 * into storage; [redacted] is the only form that may reach a log.
 */
object FeedUrl {

    /** Trimmed, `webcal(s)://` → `https://`, http(s) with a host only; null otherwise. */
    fun normalize(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val lower = s.lowercase()
        s = when {
            lower.startsWith("webcals://") -> "https://" + s.substring("webcals://".length)
            lower.startsWith("webcal://") -> "https://" + s.substring("webcal://".length)
            else -> s
        }
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (uri.host.isNullOrBlank()) return null
        return scheme + s.substring(uri.scheme.length)
    }

    /** `https://calendar.google.com/…`: scheme and host only, never the path or query. */
    fun redacted(url: String): String {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
        val host = uri?.host ?: return "(feed)"
        return "${uri.scheme ?: "https"}://$host/…"
    }
}
