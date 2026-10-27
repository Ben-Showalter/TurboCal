package com.turbocal.core.update

import com.turbocal.core.sync.HttpTransport
import org.json.JSONObject

data class ReleaseAsset(val name: String, val url: String, val sizeBytes: Long)

data class ReleaseInfo(val tag: String, val version: String, val notes: String, val asset: ReleaseAsset)

sealed class ReleaseResult {
    /** GitHub has no published (non-pre-release) release yet, or the repo isn't public. */
    object NoRelease : ReleaseResult()

    /** A release exists but has no APK this phone can install. */
    data class NoAsset(val tag: String) : ReleaseResult()

    data class Found(val info: ReleaseInfo) : ReleaseResult()

    data class Failed(val message: String) : ReleaseResult()
}

/**
 * Asks GitHub for the latest release (AGENTS.md §9). Only talks to GitHub;
 * never sends anything about the user's feeds.
 */
class ReleaseCheck(
    private val transport: HttpTransport,
    private val owner: String = OWNER,
    private val repo: String = REPO,
) {
    fun latest(supportedAbis: List<String>): ReleaseResult = try {
        transport.get(
            "https://api.github.com/repos/$owner/$repo/releases/latest",
            mapOf("Accept" to "application/vnd.github+json"),
        ).use { r ->
            when (r.code) {
                200 -> parse(r.body?.bufferedReader()?.use { it.readText() }.orEmpty(), supportedAbis)
                404 -> ReleaseResult.NoRelease
                403, 429 -> ReleaseResult.Failed("GitHub is busy - try again later")
                else -> ReleaseResult.Failed("Update check failed (${r.code})")
            }
        }
    } catch (e: Exception) {
        ReleaseResult.Failed("No connection")
    }

    companion object {
        const val OWNER = "Ben-Showalter"
        const val REPO = "TurboCal"

        fun parse(json: String, supportedAbis: List<String>): ReleaseResult {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return ReleaseResult.Failed("Unexpected reply from GitHub")
            val tag = o.optString("tag_name").trim()
            if (tag.isEmpty()) return ReleaseResult.NoRelease
            val asset = pickApk(o, supportedAbis) ?: return ReleaseResult.NoAsset(tag)
            return ReleaseResult.Found(
                ReleaseInfo(tag = tag, version = tag.removePrefix("v"), notes = o.optString("body").trim(), asset = asset),
            )
        }

        /** This phone's ABI split first (smallest), then `universal`, then a lone APK. */
        fun pickApk(release: JSONObject, supportedAbis: List<String>): ReleaseAsset? {
            val arr = release.optJSONArray("assets") ?: return null
            val apks = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
                .map { ReleaseAsset(it.optString("name"), it.optString("browser_download_url"), it.optLong("size")) }
                .filter { it.url.startsWith("https://") }
            for (abi in supportedAbis) apks.firstOrNull { it.name.contains(abi, ignoreCase = true) }?.let { return it }
            apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }?.let { return it }
            return apks.singleOrNull()
        }

        /** Launch auto-checks run at most once per day. */
        const val AUTO_CHECK_INTERVAL_MILLIS = 24 * 3_600_000L

        fun dueForAutoCheck(lastCheckAt: Long?, now: Long): Boolean =
            lastCheckAt == null || now - lastCheckAt >= AUTO_CHECK_INTERVAL_MILLIS || lastCheckAt > now
    }
}
