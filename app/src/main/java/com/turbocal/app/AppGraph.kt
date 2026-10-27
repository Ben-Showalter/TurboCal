package com.turbocal.app

import android.content.Context
import com.turbocal.app.data.CalendarRepository
import com.turbocal.app.data.Prefs
import com.turbocal.app.data.TurboCalDatabase
import com.turbocal.app.feeds.FeedFileImporter
import com.turbocal.app.sync.SyncRunner
import com.turbocal.app.update.UpdateManager
import com.turbocal.core.sync.FeedFetcher
import com.turbocal.core.sync.ManualSyncThrottle
import com.turbocal.core.sync.UrlConnectionTransport
import com.turbocal.core.update.ReleaseCheck

/** Hand-written wiring (AGENTS.md §2): one instance, built in [TurboCalApp]. */
class AppGraph(context: Context) {
    private val app = context.applicationContext
    private val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0"

    val prefs = Prefs(app)
    val database = TurboCalDatabase.create(app)
    val repository = CalendarRepository(database.dao(), prefs)
    val transport = UrlConnectionTransport(userAgent = "TurboCal/$version")
    val syncRunner = SyncRunner(database.dao(), FeedFetcher(transport), prefs)
    val throttle = ManualSyncThrottle()
    val updates = UpdateManager(app, prefs, ReleaseCheck(transport))
    val importer = FeedFileImporter(app)
}
