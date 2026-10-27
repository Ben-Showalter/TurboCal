package com.turbocal.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.turbocal.app.TurboCalApp

/** Background and on-demand sync. Per-feed errors are stored, so the work itself always succeeds. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as TurboCalApp).graph
        val feedId = inputData.getLong(KEY_FEED_ID, -1L).takeIf { it >= 0 }
        SyncState.set(true)
        try {
            graph.syncRunner.run(feedId)
        } finally {
            SyncState.set(false)
        }
        return Result.success()
    }

    companion object {
        const val KEY_FEED_ID = "feedId"
    }
}
