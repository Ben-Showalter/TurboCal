package com.turbocal.app.feeds

import android.content.Context
import android.os.Environment
import com.turbocal.core.feeds.FeedImport
import java.io.File

/**
 * Finds `turbocal_feeds.json` (AGENTS.md §7): first in the app's own external
 * folder (no permission needed), then in Download/.
 */
class FeedFileImporter(private val context: Context) {

    fun appFolderFile(): File? = context.getExternalFilesDir(null)?.let { File(it, FeedImport.FILE_NAME) }

    @Suppress("DEPRECATION") // The public Download folder path; reading it is fine with legacy storage.
    fun downloadsFile(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FeedImport.FILE_NAME)

    /** The file to import, or null when neither location has one. */
    fun find(): File? = listOfNotNull(appFolderFile(), downloadsFile()).firstOrNull { it.isFile }

    /** True when the app-folder copy exists (no storage permission needed then). */
    fun inAppFolder(): Boolean = appFolderFile()?.isFile == true

    fun read(file: File): String = file.readText(Charsets.UTF_8)
}
