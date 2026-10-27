package com.turbocal.app.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.turbocal.app.R
import com.turbocal.app.data.Prefs
import com.turbocal.core.update.ReleaseCheck
import com.turbocal.core.update.ReleaseInfo
import com.turbocal.core.update.ReleaseResult
import com.turbocal.core.update.VersionCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub Releases (AGENTS.md §9), modelled on MatChat's.
 * Checks are automatic (at most daily, on launch) or forced from Settings;
 * downloading and installing only ever happen when the user presses OK.
 */
class UpdateManager(
    private val context: Context,
    private val prefs: Prefs,
    private val check: ReleaseCheck,
) {
    private val _state = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val state: StateFlow<UpdateStatus> = _state.asStateFlow()

    fun currentVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    /** [force] = false is the launch check: throttled to once a day, silent on failure. */
    suspend fun checkForUpdate(force: Boolean) {
        when (_state.value) {
            is UpdateStatus.Checking, is UpdateStatus.Downloading, is UpdateStatus.Downloaded -> return
            else -> Unit
        }
        val now = System.currentTimeMillis()
        if (!force && !ReleaseCheck.dueForAutoCheck(prefs.lastUpdateCheckAt, now)) return
        _state.value = UpdateStatus.Checking
        val result = withContext(Dispatchers.IO) { check.latest(Build.SUPPORTED_ABIS.toList()) }
        prefs.lastUpdateCheckAt = now
        _state.value = when (result) {
            is ReleaseResult.Found ->
                if (VersionCompare.isNewer(result.info.version, currentVersion())) UpdateStatus.Available(result.info)
                else UpdateStatus.UpToDate
            is ReleaseResult.NoRelease -> UpdateStatus.NoRelease
            is ReleaseResult.NoAsset -> UpdateStatus.NoAsset(result.tag)
            is ReleaseResult.Failed -> if (force) UpdateStatus.Failed(result.message) else UpdateStatus.Idle
        }
    }

    /** Downloads the chosen APK into cache/updates/ with progress. */
    suspend fun download() {
        val info = when (val s = _state.value) {
            is UpdateStatus.Available -> s.info
            is UpdateStatus.Failed -> s.info ?: return
            else -> return
        }
        _state.value = UpdateStatus.Downloading(info, 0)
        _state.value = withContext(Dispatchers.IO) {
            try {
                val file = fetch(info)
                verify(file, info)
                UpdateStatus.Downloaded(info, file)
            } catch (e: Exception) {
                Log.w(TAG, "update download failed: ${e.javaClass.simpleName}")
                UpdateStatus.Failed(e.message?.takeIf { e is UpdateException } ?: "Download failed - check the connection", info)
            }
        }
    }

    private fun fetch(info: ReleaseInfo): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "TurboCal-${info.version}.apk")
        // GitHub redirects https → https (objects.githubusercontent.com); HttpURLConnection follows that itself.
        val conn = (URL(info.asset.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "TurboCal-Updater")
        }
        try {
            if (conn.responseCode !in 200..299) throw UpdateException("Download failed (${conn.responseCode})")
            val total = conn.contentLength.toLong().takeIf { it > 0 } ?: info.asset.sizeBytes
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (p != lastPercent) {
                                lastPercent = p
                                _state.value = UpdateStatus.Downloading(info, p)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return out
    }

    /** Refuses a file that isn't a newer TurboCal (wrong app, truncated, or not newer). */
    private fun verify(file: File, info: ReleaseInfo) {
        if (info.asset.sizeBytes > 0 && file.length() != info.asset.sizeBytes) throw UpdateException("Download was incomplete")
        val pkg = context.packageManager.getPackageArchiveInfo(file.path, 0)
            ?: throw UpdateException("The download isn't a valid APK")
        if (pkg.packageName != context.packageName) throw UpdateException("The download isn't TurboCal")
        if (!VersionCompare.isNewer(pkg.versionName.orEmpty(), currentVersion())) throw UpdateException("The download isn't newer")
    }

    /**
     * Hands the APK to the system installer. If the phone needs "install unknown
     * apps" allowed first (API 26+), opens that setting instead with a toast
     * giving the D-pad steps; the user comes back and presses Install again.
     */
    fun install(activity: Activity) {
        val s = _state.value as? UpdateStatus.Downloaded ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            openUnknownSources(activity)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", s.file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            _state.value = UpdateStatus.Failed("No installer found on this phone", s.info)
        }
    }

    private fun openUnknownSources(activity: Activity) {
        Toast.makeText(activity, R.string.update_allow_sources, Toast.LENGTH_LONG).show()
        // Per-app deep links are unreliable on OEM ROMs (guide §5); fall back to the list.
        val perApp = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        val list = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        for (i in listOf(perApp, list, Intent(Settings.ACTION_SECURITY_SETTINGS))) {
            try {
                activity.startActivity(i)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    private class UpdateException(message: String) : IOException(message)

    companion object {
        private const val TAG = "TurboCalUpdate"
        private const val TIMEOUT_MS = 30_000
    }
}
