package com.turbocal.app.sync

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** WorkManager wiring (AGENTS.md §5). Only runs with a network connection. */
object SyncScheduler {
    private const val PERIODIC = "periodic-sync"
    private const val NOW = "sync-now"

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** [minutes] = 0 turns background sync off. */
    fun schedulePeriodic(context: Context, minutes: Int) {
        val wm = WorkManager.getInstance(context)
        if (minutes <= 0) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(connected)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Queues a sync now (one feed, or all when [feedId] is null). */
    fun syncNow(context: Context, feedId: Long? = null) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(connected)
            .setInputData(workDataOf(SyncWorker.KEY_FEED_ID to (feedId ?: -1L)))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            if (feedId == null) NOW else "$NOW-$feedId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    @Suppress("DEPRECATION") // activeNetworkInfo is the simple check that works back to API 24.
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo?.isConnected == true
    }
}
