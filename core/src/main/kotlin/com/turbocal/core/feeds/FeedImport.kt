package com.turbocal.core.feeds

import org.json.JSONException
import org.json.JSONObject

/** One feed from the import file, already validated and normalised. */
data class ImportedFeed(val name: String, val url: String, val color: Int?, val enabled: Boolean)

/** A feed already in the database, as far as import cares. */
data class ExistingFeed(val id: Long, val url: String)

class ImportFormatException(message: String) : Exception(message)

data class ImportFile(val feeds: List<ImportedFeed>, val skipped: Int)

data class ImportPlan(
    val adds: List<ImportedFeed>,
    val updates: List<Pair<Long, ImportedFeed>>,
    val skipped: Int,
) {
    /** "Imported 3, updated 1, skipped 1 (bad URL)". Never mentions a URL. */
    fun summary(): String = buildString {
        append("Imported ${adds.size}, updated ${updates.size}")
        if (skipped > 0) append(", skipped $skipped (bad URL)")
    }
}

/**
 * The `turbocal_feeds.json` format (README "Importing feeds"):
 *
 * ```json
 * { "version": 1,
 *   "feeds": [ { "name": "Family", "url": "https://…", "color": "#4DB6AC", "enabled": true } ] }
 * ```
 * `color` and `enabled` are optional. A bare top-level array of feeds is accepted too.
 */
object FeedImport {
    const val FILE_NAME = "turbocal_feeds.json"

    fun parse(json: String): ImportFile {
        val text = json.trim().removePrefix("﻿")
        val array = try {
            if (text.startsWith("[")) org.json.JSONArray(text) else JSONObject(text).optJSONArray("feeds")
        } catch (e: JSONException) {
            throw ImportFormatException("The file isn't valid JSON")
        } ?: throw ImportFormatException("The file has no \"feeds\" list")

        val feeds = ArrayList<ImportedFeed>()
        var skipped = 0
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i)
            val url = o?.optString("url")?.let { FeedUrl.normalize(it) }
            if (o == null || url == null) {
                skipped++
                continue
            }
            val name = o.optString("name").trim().ifEmpty { "Calendar ${i + 1}" }
            val color = if (o.has("color")) FeedColors.parse(o.optString("color")) else null
            val enabled = if (o.has("enabled")) o.optBoolean("enabled", true) else true
            feeds += ImportedFeed(name, url, color, enabled)
        }
        return ImportFile(feeds.distinctBy { it.url }, skipped)
    }

    /** Merge by normalised URL: known feeds are updated, new ones added, none duplicated. */
    fun plan(existing: List<ExistingFeed>, file: ImportFile): ImportPlan {
        val byUrl = existing.associateBy { FeedUrl.normalize(it.url) ?: it.url }
        val adds = ArrayList<ImportedFeed>()
        val updates = ArrayList<Pair<Long, ImportedFeed>>()
        for (f in file.feeds) {
            val match = byUrl[f.url]
            if (match != null) updates += match.id to f else adds += f
        }
        return ImportPlan(adds, updates, file.skipped)
    }
}
