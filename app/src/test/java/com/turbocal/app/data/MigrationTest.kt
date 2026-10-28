package com.turbocal.app.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v1 → v2 must keep existing feeds and events (installs from before the phone calendar). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MigrationTest {
    @Test fun `v1 database migrates and keeps its data`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        // The v1 schema exactly as Room 2.6 created it.
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `feeds` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                    "`url` TEXT NOT NULL, `color` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `etag` TEXT, `lastModified` TEXT, " +
                    "`lastParsedAt` INTEGER, `lastSuccessAt` INTEGER, `lastAttemptAt` INTEGER, `lastError` TEXT)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `feedId` INTEGER NOT NULL, " +
                    "`uid` TEXT NOT NULL, `recurrenceKey` TEXT NOT NULL, `title` TEXT NOT NULL, `location` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, `kind` INTEGER NOT NULL, `startKey` INTEGER NOT NULL, `endKey` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`feedId`) REFERENCES `feeds`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_feedId` ON `events` (`feedId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_startKey` ON `events` (`startKey`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_endKey` ON `events` (`endKey`)")
            db.execSQL("INSERT INTO feeds (name, url, color, enabled) VALUES ('Family', 'https://example.invalid/f.ics', 1, 1)")
            db.execSQL(
                "INSERT INTO events (feedId, uid, recurrenceKey, title, location, description, kind, startKey, endKey) " +
                    "VALUES (1, 'a', '', 'Hello', '', '', 1, 1000, 2000)",
            )
            db.version = 1
        }

        val room = Room.databaseBuilder(context, TurboCalDatabase::class.java, name)
            .addMigrations(TurboCalDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        val feeds = room.dao().allFeeds()
        assertEquals(listOf("Family"), feeds.map { it.name })
        assertEquals(FeedEntity.SOURCE_ICS, feeds.single().source)
        assertFalse(feeds.single().isLocal)
        assertEquals("Hello", room.dao().event(1)?.title)
        assertEquals(null, room.dao().localFeed())
        room.close()
    }
}
