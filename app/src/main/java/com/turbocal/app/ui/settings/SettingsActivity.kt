package com.turbocal.app.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.turbocal.app.R
import com.turbocal.app.data.AppTheme
import com.turbocal.app.data.FeedEntity
import com.turbocal.app.data.Prefs
import com.turbocal.app.sync.SyncScheduler
import com.turbocal.app.sync.SyncState
import com.turbocal.app.ui.base.CalendarActivity
import com.turbocal.app.ui.base.FlipBaseActivity
import com.turbocal.app.notify.BackgroundAccess
import com.turbocal.app.ui.calendars.CalendarsActivity
import com.turbocal.app.ui.eventedit.KeypadPicker
import com.turbocal.core.notify.Reminders
import java.time.LocalTime
import com.turbocal.app.ui.update.UpdateActivity
import com.turbocal.app.update.UpdateStatus
import com.turbocal.core.display.Formats
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Locale

/**
 * Settings: a menu of three pages (AGENTS.md §8), each its own screen.
 *  - Sync: sync now, interval.
 *  - Calendars: opens the Calendars screen (CalendarsActivity).
 *  - App: theme, show disabled calendars, software update, key help.
 * One list per page; OK acts on the selected row; Back returns to the menu.
 */
class SettingsActivity : FlipBaseActivity() {

    enum class Page { MENU, SYNC, NOTIFICATIONS, APP }

    private sealed class Item {
        abstract val id: Long
        data class Header(val text: String, override val id: Long) : Item()
        data class Action(val key: String, val title: String, val subtitle: String, override val id: Long) : Item()
        data class Note(val text: String, override val id: Long) : Item()
    }

    private lateinit var page: Page
    private lateinit var list: ListView
    private val adapter = Adapter()
    private var feeds: List<FeedEntity> = emptyList()
    private var updateStatus: UpdateStatus = UpdateStatus.Idle
    private var syncing = false
    private val formats by lazy { Formats(Locale.getDefault(), DateFormat.is24HourFormat(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = Page.values().firstOrNull { it.name == intent.getStringExtra(EXTRA_PAGE) } ?: Page.MENU
        setContentView(R.layout.activity_list)
        setTitleText(
            when (page) {
                Page.MENU -> getString(R.string.title_settings)
                Page.SYNC -> getString(R.string.settings_header_sync)
                Page.NOTIFICATIONS -> getString(R.string.settings_header_notifications)
                Page.APP -> getString(R.string.settings_header_app)
            },
        )
        setSoftkeys(getString(R.string.key_sync), getString(R.string.key_select), getString(R.string.key_keys))
        list = findViewById(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> onItem(adapter.items[position]) }

        scope.launch {
            combine(graph.repository.feeds, SyncState.running, graph.updates.state) { f, s, u ->
                State(f, s, u)
            }.collect { st ->
                feeds = st.feeds
                syncing = st.syncing
                updateStatus = st.update
                render()
            }
        }
        list.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // Back from the battery settings: refresh "Run in background".
        if (::list.isInitialized && page == Page.NOTIFICATIONS) render()
    }

    private data class State(val feeds: List<FeedEntity>, val syncing: Boolean, val update: UpdateStatus)

    private fun render() {
        val items = when (page) {
            Page.MENU -> menuItems()
            Page.SYNC -> syncItems()
            Page.NOTIFICATIONS -> notificationItems()
            Page.APP -> appItems()
        }
        val selectedId = list.selectedItemId.takeIf { list.selectedItemPosition >= 0 }
        adapter.items = items
        adapter.notifyDataSetChanged()
        val index = selectedId?.let { id -> items.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
            ?: items.indexOfFirst { it is Item.Action }.coerceAtLeast(0)
        list.setSelection(index)
    }

    private fun syncSummary(): String {
        val now = System.currentTimeMillis()
        val links = feeds.filter { it.enabled && !it.isLocal }
        return when {
            syncing -> getString(R.string.status_syncing)
            links.any { it.lastError != null } -> getString(R.string.status_error)
            else -> formats.synced(links.mapNotNull { it.lastSuccessAt }.minOrNull(), now, ZoneId.systemDefault())
        }
    }

    private fun menuItems(): List<Item> {
        val errors = feeds.count { it.enabled && !it.isLocal && it.lastError != null }
        val calendars = resources.getQuantityString(R.plurals.settings_calendar_count, feeds.size, feeds.size) +
            if (errors > 0) " · " + resources.getQuantityString(R.plurals.settings_error_count, errors, errors) else ""
        val badge = (updateStatus as? UpdateStatus.Available)?.let { " · " + getString(R.string.update_badge, it.info.tag) } ?: ""
        val app = getString(R.string.settings_app_summary, graph.prefs.theme.label, graph.updates.currentVersion()) + badge
        return listOf(
            Item.Action("page:SYNC", getString(R.string.settings_header_sync), syncSummary(), 1),
            Item.Action("page:NOTIFICATIONS", getString(R.string.settings_header_notifications), notifySummary(), 4),
            Item.Action("calendars", getString(R.string.settings_header_calendars), calendars, 2),
            Item.Action("page:APP", getString(R.string.settings_header_app), app, 3),
        )
    }

    private fun syncItems(): List<Item> = listOf(
        Item.Action("sync", getString(R.string.settings_sync_now), syncSummary(), 1),
        Item.Action("interval", getString(R.string.settings_interval), Prefs.intervalLabel(graph.prefs.syncIntervalMinutes), 2),
    )

    private fun leadLabel(minutes: Int): String =
        if (minutes == 0) getString(R.string.notify_lead_at_start) else getString(R.string.notify_lead_before, minutes)

    private fun allDayTime(): LocalTime = LocalTime.of(graph.prefs.allDayNotifyMinutes / 60, graph.prefs.allDayNotifyMinutes % 60)

    private fun notifySummary(): String =
        if (!graph.prefs.notifyEnabled) getString(R.string.off)
        else getString(R.string.notify_summary_on, leadLabel(graph.prefs.notifyLeadMinutes).lowercase(), formats.time(allDayTime()))

    /** Settings > Notifications (owner, round 7): on by default, 5 min before, all-day at 8:00 AM. */
    private fun notificationItems(): List<Item> {
        val on = graph.prefs.notifyEnabled
        val items = arrayListOf<Item>(
            Item.Action("notify_on", getString(R.string.notify_enabled), getString(if (on) R.string.on else R.string.off), 1),
        )
        if (on) {
            items += Item.Action("notify_lead", getString(R.string.notify_lead), leadLabel(graph.prefs.notifyLeadMinutes), 2)
            items += Item.Action("notify_allday", getString(R.string.notify_allday), formats.time(allDayTime()), 3)
            items += Item.Action(
                "notify_cover", getString(R.string.notify_cover),
                getString(if (graph.prefs.coverCards) R.string.on else R.string.off) + " · " + getString(R.string.notify_cover_sub), 4,
            )
            items += Item.Action(
                "background", getString(R.string.background_row),
                getString(if (BackgroundAccess.isExempt(this)) R.string.background_allowed else R.string.background_not_allowed), 6,
            )
            items += Item.Action("notify_test", getString(R.string.notify_test), "", 5)
        }
        return items
    }

    /** Any notification setting changed: redraw and move the alarm. */
    private fun notifyChanged() {
        render()
        graph.reminders.rescheduleAsync()
    }

    private fun appItems(): List<Item> {
        val badge = (updateStatus as? UpdateStatus.Available)?.let { " · " + getString(R.string.update_badge, it.info.tag) } ?: ""
        return listOf(
            Item.Action("theme", getString(R.string.settings_theme), graph.prefs.theme.label, 1),
            Item.Action("update", getString(R.string.settings_update), getString(R.string.update_installed, graph.updates.currentVersion()) + badge, 3),
            Item.Action("keys", getString(R.string.menu_keys), "", 4),
        )
    }

    private fun onItem(item: Item) {
        when (item) {
            is Item.Action -> when {
                item.key.startsWith("page:") -> startActivity(
                    Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, item.key.removePrefix("page:")),
                )
                item.key == "sync" -> CalendarActivity.syncNow(this)
                item.key == "interval" -> chooseInterval()
                item.key == "calendars" -> startActivity(Intent(this, CalendarsActivity::class.java))
                item.key == "notify_on" -> {
                    graph.prefs.notifyEnabled = !graph.prefs.notifyEnabled
                    notifyChanged()
                }
                item.key == "notify_lead" -> {
                    val choices = Reminders.LEAD_CHOICES
                    graph.prefs.notifyLeadMinutes = choices[(choices.indexOf(graph.prefs.notifyLeadMinutes) + 1) % choices.size]
                    notifyChanged()
                }
                item.key == "notify_allday" -> KeypadPicker.pickTime(this, getString(R.string.picker_allday_time), allDayTime(), null) {
                    graph.prefs.allDayNotifyMinutes = it.hour * 60 + it.minute
                    notifyChanged()
                }
                item.key == "notify_cover" -> {
                    graph.prefs.coverCards = !graph.prefs.coverCards
                    render()
                }
                item.key == "background" -> BackgroundAccess.request(this)
                item.key == "notify_test" -> {
                    graph.reminders.sendTest()
                    toast(R.string.notify_test_sent)
                }
                item.key == "theme" -> toggleTheme()
                item.key == "update" -> startActivity(Intent(this, UpdateActivity::class.java))
                item.key == "keys" -> onOptionsKey()
            }
            else -> Unit
        }
    }

    override fun onPrimaryKey() = CalendarActivity.syncNow(this)

    override fun onOptionsKey() {
        AlertDialog.Builder(this).setMessage(R.string.keys_help).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun toggleTheme() {
        graph.prefs.theme = if (graph.prefs.theme == AppTheme.DARK) AppTheme.LIGHT else AppTheme.DARK
        recreate() // this page now; the screens underneath repaint when they resume
    }

    private fun chooseInterval() {
        val choices = Prefs.INTERVAL_CHOICES
        val current = choices.indexOf(graph.prefs.syncIntervalMinutes).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_interval)
            .setSingleChoiceItems(choices.map { Prefs.intervalLabel(it) }.toTypedArray(), current) { d, which ->
                graph.prefs.syncIntervalMinutes = choices[which]
                SyncScheduler.schedulePeriodic(this, choices[which])
                d.dismiss()
                render()
            }
            .show()
    }

    private inner class Adapter : BaseAdapter() {
        var items: List<Item> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun hasStableIds() = true
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = items[position] is Item.Action
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position] is Item.Header || items[position] is Item.Note) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            if (item is Item.Header || item is Item.Note) {
                val v = (convertView ?: layoutInflater.inflate(R.layout.row_header, parent, false)) as TextView
                v.text = if (item is Item.Header) item.text else (item as Item.Note).text
                v.setTextColor(themeColor(if (item is Item.Header) R.attr.tcAccentText else R.attr.tcTextSecondary))
                v.maxLines = if (item is Item.Note) 3 else 1
                return v
            }
            val v = convertView ?: layoutInflater.inflate(R.layout.row_setting, parent, false)
            val bar = v.findViewById<View>(R.id.color_bar)
            val title = v.findViewById<TextView>(R.id.title)
            val sub = v.findViewById<TextView>(R.id.subtitle)
            when (item) {
                is Item.Action -> {
                    bar.visibility = View.INVISIBLE
                    title.text = item.title
                    title.setTextColor(themeColor(R.attr.tcTextPrimary))
                    sub.text = item.subtitle
                    sub.visibility = if (item.subtitle.isEmpty()) View.GONE else View.VISIBLE
                    sub.setTextColor(themeColor(R.attr.tcTextSecondary))
                }
                else -> Unit
            }
            return v
        }
    }

    companion object {
        const val EXTRA_PAGE = "page"
    }
}
