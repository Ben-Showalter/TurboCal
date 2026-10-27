package com.turbocal.core.update

import com.turbocal.core.sync.HttpResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    @Test fun `version compare is numeric and pre-releases sort lower`() {
        assertTrue(VersionCompare.isNewer("v1.2.10", "1.2.9"))
        assertTrue(VersionCompare.isNewer("1.3.0", "1.3.0-beta1"))
        assertFalse(VersionCompare.isNewer("1.3.0-beta1", "1.3.0"))
        assertFalse(VersionCompare.isNewer("v0.1.0", "0.1.0"))
        assertTrue(VersionCompare.isNewer("0.2", "0.1.9"))
        assertEquals(0, VersionCompare.compare("1.0", "1.0.0"))
        assertTrue(VersionCompare.isNewer("1.0.0-rc2", "1.0.0-rc1"))
    }

    private val release = """
        { "tag_name": "v0.2.0", "body": "Fixes the month view.\n",
          "assets": [
            { "name": "TurboCal-0.2.0-universal.apk", "size": 9000000, "browser_download_url": "https://github.com/x/universal.apk" },
            { "name": "TurboCal-0.2.0-armeabi-v7a.apk", "size": 4000000, "browser_download_url": "https://github.com/x/v7a.apk" },
            { "name": "TurboCal-0.2.0-arm64-v8a.apk", "size": 4100000, "browser_download_url": "https://github.com/x/v8a.apk" },
            { "name": "notes.txt", "size": 10, "browser_download_url": "https://github.com/x/notes.txt" }
          ] }
    """.trimIndent()

    @Test fun `picks this phone's ABI split first, then universal`() {
        val found = ReleaseCheck.parse(release, listOf("armeabi-v7a", "armeabi")) as ReleaseResult.Found
        assertEquals("TurboCal-0.2.0-armeabi-v7a.apk", found.info.asset.name)
        assertEquals("0.2.0", found.info.version)
        assertEquals("Fixes the month view.", found.info.notes)
        val universal = ReleaseCheck.pickApk(JSONObject(release), listOf("x86_64"))
        assertEquals("TurboCal-0.2.0-universal.apk", universal?.name)
    }

    @Test fun `release without an apk, and no release`() {
        assertEquals(ReleaseResult.NoAsset("v1"), ReleaseCheck.parse("""{"tag_name":"v1","assets":[]}""", listOf("arm64-v8a")))
        val none = ReleaseCheck({ _, _ -> HttpResponse(404, emptyMap(), null) }).latest(listOf("arm64-v8a"))
        assertEquals(ReleaseResult.NoRelease, none)
    }

    @Test fun `check calls the latest-release endpoint`() {
        var asked = ""
        val r = ReleaseCheck({ url, _ -> asked = url; HttpResponse(200, emptyMap(), release.byteInputStream()) })
            .latest(listOf("arm64-v8a"))
        assertEquals("https://api.github.com/repos/Ben-Showalter/TurboCal/releases/latest", asked)
        assertEquals("TurboCal-0.2.0-arm64-v8a.apk", (r as ReleaseResult.Found).info.asset.name)
    }

    @Test fun `auto check runs at most daily`() {
        val day = ReleaseCheck.AUTO_CHECK_INTERVAL_MILLIS
        assertTrue(ReleaseCheck.dueForAutoCheck(null, 5))
        assertFalse(ReleaseCheck.dueForAutoCheck(1000, 1000 + day - 1))
        assertTrue(ReleaseCheck.dueForAutoCheck(1000, 1000 + day))
        assertTrue(ReleaseCheck.dueForAutoCheck(10 * day, 1)) // clock went backwards
    }
}
