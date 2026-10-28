package com.turbocal.app.feeds

import android.content.Context
import android.os.Environment
import com.turbocal.core.feeds.FeedImport
import java.io.File

/**
 * Finds files the user pushed with adb (AGENTS.md §7): `turbocal_feeds.json`
 * and the phone calendar's `turbocal_local.ics`. First the app's own external
 * folder (no permission needed), then Download/.
 */
class FeedFileImporter(private val context: Context) {

    fun appFolderFile(name: String = FeedImport.FILE_NAME): File? = context.getExternalFilesDir(null)?.let { File(it, name) }

    @Suppress("DEPRECATION") // The public Download folder path; reading it is fine with legacy storage.
    fun downloadsFile(name: String = FeedImport.FILE_NAME): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)

    /** The file to read, or null when neither location has one. */
    fun find(name: String = FeedImport.FILE_NAME): File? = listOfNotNull(appFolderFile(name), downloadsFile(name)).firstOrNull { it.isFile }

    /** True when the app-folder copy exists (no storage permission needed then). */
    fun inAppFolder(name: String = FeedImport.FILE_NAME): Boolean = appFolderFile(name)?.isFile == true

    fun read(file: File): String = file.readText(Charsets.UTF_8)
}
