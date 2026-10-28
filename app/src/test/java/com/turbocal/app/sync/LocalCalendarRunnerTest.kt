package com.turbocal.app.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.turbocal.app.data.TurboCalDatabase
import com.turbocal.core.local.LocalEvent
import com.turbocal.core.local.RepeatChoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocalCalendarRunnerTest {
    private lateinit var db: TurboCalDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), TurboCalDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun all() = db.dao().eventsBetween(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2, true)

    @Test fun `save expands, delete-one excludes, export and import round-trip`() = runTest {
        val runner = LocalCalendarRunner(db.dao())
        val start = LocalDate.now().plusDays(1).atTime(9, 0)
        val e = LocalEvent("u1", "Walk", "", "", false, start, start.plusHours(1), RepeatChoice.DAILY.rrule.let { "$it;COUNT=3" })
        runner.save(e)
        assertTrue(db.dao().localFeed()!!.isLocal)
        assertEquals(3, all().first().size)

        val second = db.dao().eventRef(all().first()[1].id)!!
        runner.deleteOccurrence(second.uid, second.recurrenceKey)
        assertEquals(2, all().first().size)

        val file = File.createTempFile("local", ".ics")
        assertEquals(1, runner.exportTo(file))
        runner.delete("u1")
        assertEquals(0, all().first().size)
        val back = runner.importFrom(file)
        assertEquals(1, back.events.size)
        assertEquals(2, all().first().size) // the exclusion came back with it
    }
}
