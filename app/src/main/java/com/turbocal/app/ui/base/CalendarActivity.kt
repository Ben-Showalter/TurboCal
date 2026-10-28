package com.turbocal.app.ui.base

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ListView
import android.text.format.DateFormat
import com.turbocal.app.R
import com.turbocal.app.data.CalendarView
import com.turbocal.app.sync.SyncScheduler
import com.turbocal.app.sync.SyncState
import com.turbocal.app.ui.agenda.AgendaActivity
import com.turbocal.app.ui.calendars.CalendarsActivity
import com.turbocal.app.ui.day.DayActivity
import com.turbocal.app.ui.eventedit.EventEditActivity
import com.turbocal.app.ui.month.MonthActivity
import com.turbocal.app.ui.settings.SettingsActivity
import com.turbocal.app.ui.week.WeekActivity
import com.turbocal.core.display.Formats
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * What Month, Week, Day and Agenda share (AGENTS.md §8): the View key cycles
 * them in that order, Options opens the menu, the number keys are shortcuts,
 * and the status line shows sync state. Views stack (see [openView]), so
 * Back/Clear returns to the previous view and End goes home (owner, round 6);
 * the launcher reopens the last view.
 */
abstract class CalendarActivity : FlipBaseActivity() {

    override val handlesHorizontalKeys = true

    protected val zone: ZoneId get() = ZoneId.systemDefault()
    protected val today: LocalDate get() = LocalDate.now(zone)
    protected val formats: Formats by lazy { Formats(Locale.getDefault(), DateFormat.is24HourFormat(this)) }

    /** The day this screen is about; passed on when switching view. */
    protected abstract val currentDate: LocalDate

    /** Which of the four views this is. */
    protected abstract val view: CalendarView
    protected abstract fun step(direction: Int)
    protected abstract fun goToday()

    private var openedOn: LocalDate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedOn = today
        scope.launch { graph.updates.checkForUpdate(force = false) } // at most daily
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || recreating) return
        graph.prefs.lastView = view
        // Left open past midnight: rebuild so "Today" and the window move on.
        if (openedOn != null && openedOn != today) {
            openedOn = today
            jump(intentFor(this, javaClass, today))
        }
    }

    /** The calendar views' lists use the rounded selection box (owner, round 4); Settings keeps the square one. */
    protected fun useRoundedSelector(list: ListView) {
        list.selector = getDrawable(R.drawable.list_row_selector_rounded)
    }

    /** Call after setContentView: wires the status line. */
    protected fun observeStatus() {
        scope.launch {
            combine(graph.repository.feeds, SyncState.running) { feeds, running -> feeds to running }.collect { (all, running) ->
                val feeds = all.filter { !it.isLocal }
                val enabled = feeds.filter { it.enabled }
                setStatusText(
                    when {
                        running -> getString(R.string.status_syncing)
                        feeds.isEmpty() && all.isEmpty() -> getString(R.string.status_no_feeds)
                        feeds.isEmpty() -> getString(R.string.status_phone_only)
                        enabled.any { it.lastError != null } -> getString(R.string.status_error)
                        else -> formats.synced(enabled.mapNotNull { it.lastSuccessAt }.minOrNull(), System.currentTimeMillis(), zone)
                    },
                )
            }
        }
    }

    override fun onPrimaryKey() = switchTo(view.next())

    private fun switchTo(target: CalendarView) {
        if (target != view) openView(classFor(target), currentDate)
    }

    /**
     * Opens another calendar view on [date], stacked on this one so Back returns
     * here (owner, round 6). REORDER_TO_FRONT reuses a view already in the stack
     * (it gets the date through onNewIntent), so there is never more than one of each.
     */
    protected fun openView(cls: Class<out CalendarActivity>, date: LocalDate) {
        startActivity(intentFor(this, cls, date).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        overridePendingTransition(0, 0)
    }

    override fun onOptionsKey() {
        // Kept short (owner): views switch with the View key; updates and key help live in Settings > App.
        // Calendars (show/hide, add) added in round 6.
        val showing = graph.prefs.showDisabled.value
        val actions = listOf<Pair<String, () -> Unit>>(
            getString(R.string.menu_new_event) to { newEvent() },
            getString(R.string.menu_sync) to { syncNow() },
            getString(R.string.menu_today) to { goToday() },
            getString(R.string.menu_settings) to { openSettings() },
            getString(R.string.menu_calendars) to { startActivity(Intent(this, CalendarsActivity::class.java)) },
            getString(if (showing) R.string.menu_hide_disabled else R.string.menu_show_disabled) to { toggleDisabled() },
        )
        AlertDialog.Builder(this).setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }.show()
    }

    /** Options > New event: the phone calendar's editor, starting on [currentDate]. */
    protected fun newEvent() {
        startActivity(Intent(this, EventEditActivity::class.java).putExtra(EventEditActivity.EXTRA_DATE, currentDate.toEpochDay()))
    }

    override fun onKey(key: Key): Boolean {
        when (key) {
            Key.LEFT, Key.PREV -> step(-1)
            Key.RIGHT, Key.NEXT -> step(1)
            Key.TODAY -> goToday()
            Key.SYNC -> syncNow()
            Key.SETTINGS -> openSettings()
            Key.TOGGLE_DISABLED -> toggleDisabled()
            else -> return false
        }
        return true
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun toggleDisabled() {
        val show = !graph.prefs.showDisabled.value
        graph.prefs.setShowDisabled(show)
        toast(if (show) R.string.toast_showing_disabled else R.string.toast_hiding_disabled)
    }

    protected fun syncNow() = Companion.syncNow(this)

    companion object {
        const val EXTRA_DATE = "date"

        fun classFor(view: CalendarView): Class<out CalendarActivity> = when (view) {
            CalendarView.MONTH -> MonthActivity::class.java
            CalendarView.WEEK -> WeekActivity::class.java
            CalendarView.DAY -> DayActivity::class.java
            CalendarView.AGENDA -> AgendaActivity::class.java
        }

        fun intentFor(context: Context, cls: Class<out CalendarActivity>, date: LocalDate): Intent =
            Intent(context, cls).putExtra(EXTRA_DATE, date.toEpochDay())

        fun dateFrom(intent: Intent?, fallback: LocalDate): LocalDate =
            intent?.getLongExtra(EXTRA_DATE, Long.MIN_VALUE)?.takeIf { it != Long.MIN_VALUE }?.let { LocalDate.ofEpochDay(it) } ?: fallback

        /** Manual sync (key 0 / menu / Settings): throttled, and explains itself when offline. */
        fun syncNow(activity: FlipBaseActivity) {
            val graph = (activity.application as com.turbocal.app.TurboCalApp).graph
            val ctx = activity.applicationContext
            val toast = { s: String -> android.widget.Toast.makeText(activity, s, android.widget.Toast.LENGTH_SHORT).show() }
            activity.launchOnScreen {
                val feeds = graph.database.dao().allFeeds().filter { !it.isLocal }
                when {
                    feeds.isEmpty() -> toast(ctx.getString(R.string.toast_no_links))
                    !SyncScheduler.isOnline(ctx) -> toast(ctx.getString(R.string.toast_offline)).also { SyncScheduler.syncNow(ctx) }
                    else -> {
                        val wait = if (feeds.any { it.lastError != null }) null
                        else graph.throttle.minutesToWait(graph.prefs.lastCleanSyncAt, System.currentTimeMillis())
                        if (wait != null) {
                            toast(graph.throttle.message(wait))
                        } else {
                            SyncScheduler.syncNow(ctx)
                            toast(ctx.getString(R.string.toast_sync_started))
                        }
                    }
                }
            }
        }
    }
}
