package com.turbocal.app.ui.base

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import com.turbocal.app.R
import com.turbocal.app.sync.SyncScheduler
import com.turbocal.app.sync.SyncState
import com.turbocal.app.ui.agenda.AgendaActivity
import com.turbocal.app.ui.day.DayActivity
import com.turbocal.app.ui.month.MonthActivity
import com.turbocal.app.ui.settings.SettingsActivity
import com.turbocal.app.ui.update.UpdateActivity
import com.turbocal.core.display.Formats
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * What Agenda, Day and Month share (AGENTS.md §8): the View key cycles
 * Agenda → Day → Month, Options opens the menu, the number keys are
 * shortcuts, and the status line shows sync state.
 */
abstract class CalendarActivity : FlipBaseActivity() {

    override val handlesHorizontalKeys = true

    protected val zone: ZoneId get() = ZoneId.systemDefault()
    protected val today: LocalDate get() = LocalDate.now(zone)
    protected val formats: Formats by lazy { Formats(Locale.getDefault(), DateFormat.is24HourFormat(this)) }

    /** The day this screen is about; passed on when switching view. */
    protected abstract val currentDate: LocalDate

    protected abstract fun nextView(): Class<out CalendarActivity>
    protected abstract fun step(direction: Int)
    protected abstract fun goToday()

    private var openedOn: LocalDate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedOn = today
    }

    override fun onResume() {
        super.onResume()
        // Left open past midnight: rebuild so "Today" and the window move on.
        if (openedOn != null && openedOn != today) {
            openedOn = today
            jump(intentFor(this, javaClass, today))
        }
    }

    /** Call after setContentView: wires the status line. */
    protected fun observeStatus() {
        scope.launch {
            combine(graph.repository.feeds, SyncState.running) { feeds, running -> feeds to running }.collect { (feeds, running) ->
                val enabled = feeds.filter { it.enabled }
                setStatusText(
                    when {
                        running -> getString(R.string.status_syncing)
                        feeds.isEmpty() -> getString(R.string.status_no_feeds)
                        enabled.any { it.lastError != null } -> getString(R.string.status_error)
                        else -> formats.synced(enabled.mapNotNull { it.lastSuccessAt }.minOrNull(), System.currentTimeMillis(), zone)
                    },
                )
            }
        }
    }

    override fun onPrimaryKey() {
        jump(intentFor(this, nextView(), currentDate))
    }

    override fun onOptionsKey() {
        val showing = graph.prefs.showDisabled.value
        val items = arrayOf(
            getString(R.string.menu_sync),
            getString(R.string.menu_today),
            getString(R.string.menu_settings),
            getString(if (showing) R.string.menu_hide_disabled else R.string.menu_show_disabled),
            getString(R.string.menu_update),
            getString(R.string.menu_keys),
        )
        AlertDialog.Builder(this).setItems(items) { _, which ->
            when (which) {
                0 -> syncNow()
                1 -> goToday()
                2 -> openSettings()
                3 -> toggleDisabled()
                4 -> startActivity(Intent(this, UpdateActivity::class.java))
                5 -> AlertDialog.Builder(this).setMessage(R.string.keys_help).setPositiveButton(android.R.string.ok, null).show()
            }
        }.show()
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

    /** Day and Month were reached sideways, so Back goes to the Agenda instead of quitting (guide §1). */
    @Deprecated("Back still arrives here on every API level this app targets")
    override fun onBackPressed() {
        if (this is AgendaActivity) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else {
            jump(intentFor(this, AgendaActivity::class.java, currentDate))
        }
    }

    companion object {
        const val EXTRA_DATE = "date"

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
                val feeds = graph.database.dao().allFeeds()
                when {
                    feeds.none { it.enabled } -> toast(ctx.getString(R.string.toast_no_feeds))
                    !SyncScheduler.isOnline(ctx) -> toast(ctx.getString(R.string.toast_offline)).also { SyncScheduler.syncNow(ctx) }
                    else -> {
                        val wait = if (feeds.any { it.enabled && it.lastError != null }) null
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
