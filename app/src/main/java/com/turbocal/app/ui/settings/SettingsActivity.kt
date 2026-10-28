package com.turbocal.app.ui.settings

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
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
import com.turbocal.app.ui.feededit.FeedEditActivity
import com.turbocal.app.ui.update.UpdateActivity
import com.turbocal.app.update.UpdateStatus
import com.turbocal.core.display.Formats
import com.turbocal.core.feeds.FeedImport
import com.turbocal.core.feeds.ImportFormatException
import com.turbocal.core.local.LocalCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.Locale

/**
 * Settings: a menu of three pages (AGENTS.md §8), each its own screen.
 *  - Sync: sync now, interval.
 *  - Calendars: import, add by link, the phone calendar (import/export .ics), every feed with its status.
 *  - App: theme, show disabled calendars, software update, key help.
 * One list per page; OK acts on the selected row; Back returns to the menu.
 */
class SettingsActivity : FlipBaseActivity() {

    enum class Page { MENU, SYNC, CALENDARS, APP }

    private sealed class Item {
        abstract val id: Long
        data class Header(val text: String, override val id: Long) : Item()
        data class Action(val key: String, val title: String, val subtitle: String, override val id: Long) : Item()
        data class Feed(val feed: FeedEntity, val subtitle: String) : Item() {
            override val id get() = 1000 + feed.id
        }
        data class Note(val text: String, override val id: Long) : Item()
    }

    private lateinit var page: Page
    private lateinit var list: ListView
    private val adapter = Adapter()
    private var feeds: List<FeedEntity> = emptyList()
    private var localCount = 0
    private var updateStatus: UpdateStatus = UpdateStatus.Idle
    private var syncing = false
    private val formats by lazy { Formats(Locale.getDefault(), DateFormat.is24HourFormat(this)) }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = Page.values().firstOrNull { it.name == intent.getStringExtra(EXTRA_PAGE) } ?: Page.MENU
        setContentView(R.layout.activity_list)
        setTitleText(
            when (page) {
                Page.MENU -> getString(R.string.title_settings)
                Page.SYNC -> getString(R.string.settings_header_sync)
                Page.CALENDARS -> getString(R.string.settings_header_calendars)
                Page.APP -> getString(R.string.settings_header_app)
            },
        )
        setSoftkeys(getString(R.string.key_sync), getString(R.string.key_select), getString(R.string.key_keys))
        list = findViewById(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> onItem(adapter.items[position]) }

        val localCounts = graph.repository.feeds.flatMapLatest { all ->
            val local = all.firstOrNull { it.isLocal }
            if (local == null) flowOf(0) else graph.repository.localEventCount(local.id)
        }
        scope.launch {
            combine(graph.repository.feeds, localCounts, SyncState.running, graph.updates.state, graph.prefs.showDisabled) { f, n, s, u, _ ->
                State(f, n, s, u)
            }.collect { st ->
                feeds = st.feeds
                localCount = st.localCount
                syncing = st.syncing
                updateStatus = st.update
                render()
            }
        }
        list.requestFocus()
    }

    private data class State(val feeds: List<FeedEntity>, val localCount: Int, val syncing: Boolean, val update: UpdateStatus)

    private fun render() {
        val items = when (page) {
            Page.MENU -> menuItems()
            Page.SYNC -> syncItems()
            Page.CALENDARS -> calendarItems()
            Page.APP -> appItems()
        }
        val selectedId = list.selectedItemId.takeIf { list.selectedItemPosition >= 0 }
        adapter.items = items
        adapter.notifyDataSetChanged()
        val index = selectedId?.let { id -> items.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
            ?: items.indexOfFirst { it is Item.Action || it is Item.Feed }.coerceAtLeast(0)
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
            Item.Action("page:CALENDARS", getString(R.string.settings_header_calendars), calendars, 2),
            Item.Action("page:APP", getString(R.string.settings_header_app), app, 3),
        )
    }

    private fun syncItems(): List<Item> = listOf(
        Item.Action("sync", getString(R.string.settings_sync_now), syncSummary(), 1),
        Item.Action("interval", getString(R.string.settings_interval), Prefs.intervalLabel(graph.prefs.syncIntervalMinutes), 2),
    )

    private fun calendarItems(): List<Item> {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val items = ArrayList<Item>()
        items += Item.Header(getString(R.string.settings_header_links), 1)
        items += Item.Action("import", getString(R.string.settings_import), getString(R.string.settings_import_sub), 2)
        items += Item.Action("add", getString(R.string.settings_add), "", 3)
        items += Item.Header(getString(R.string.settings_header_phone), 4)
        items += Item.Action("local_import", getString(R.string.settings_local_import), getString(R.string.settings_local_file), 5)
        items += Item.Action("local_export", getString(R.string.settings_local_export), getString(R.string.settings_local_file), 6)
        items += Item.Header(getString(R.string.settings_header_your_calendars), 7)
        if (feeds.isEmpty()) items += Item.Note(getString(R.string.settings_no_feeds), 8)
        for (f in feeds) {
            val sub = if (f.isLocal) {
                (if (f.enabled) "" else getString(R.string.off) + " · ") +
                    resources.getQuantityString(R.plurals.settings_local_count, localCount, localCount)
            } else {
                formats.feedStatus(f.enabled, f.lastSuccessAt, f.lastError, now, zone)
            }
            items += Item.Feed(f, sub)
        }
        return items
    }

    private fun appItems(): List<Item> {
        val badge = (updateStatus as? UpdateStatus.Available)?.let { " · " + getString(R.string.update_badge, it.info.tag) } ?: ""
        return listOf(
            Item.Action("theme", getString(R.string.settings_theme), graph.prefs.theme.label, 1),
            Item.Action(
                "disabled", getString(R.string.settings_show_disabled),
                getString(if (graph.prefs.showDisabled.value) R.string.on else R.string.off), 2,
            ),
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
                item.key == "import" -> withStorage(::runImport)
                item.key == "add" -> startActivity(Intent(this, FeedEditActivity::class.java))
                item.key == "local_import" -> withStorage(::runLocalImport)
                item.key == "local_export" -> runLocalExport()
                item.key == "theme" -> toggleTheme()
                item.key == "disabled" -> graph.prefs.setShowDisabled(!graph.prefs.showDisabled.value)
                item.key == "update" -> startActivity(Intent(this, UpdateActivity::class.java))
                item.key == "keys" -> onOptionsKey()
            }
            is Item.Feed -> feedMenu(item.feed)
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

    private fun feedMenu(feed: FeedEntity) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += getString(R.string.feed_menu_edit) to {
            startActivity(Intent(this, FeedEditActivity::class.java).putExtra(FeedEditActivity.EXTRA_ID, feed.id))
        }
        actions += getString(if (feed.enabled) R.string.feed_menu_turn_off else R.string.feed_menu_turn_on) to {
            scope.launch {
                graph.repository.setEnabled(feed.id, !feed.enabled)
                if (!feed.enabled && !feed.isLocal) SyncScheduler.syncNow(this@SettingsActivity, feed.id)
            }
            Unit
        }
        if (!feed.isLocal) {
            actions += getString(R.string.feed_menu_sync) to {
                if (feed.enabled) {
                    SyncScheduler.syncNow(this, feed.id)
                    toast(R.string.toast_sync_started)
                }
            }
        }
        actions += getString(R.string.feed_menu_delete) to { confirmDelete(feed) }
        AlertDialog.Builder(this).setTitle(feed.name).setItems(actions.map { it.first }.toTypedArray()) { _, which ->
            actions[which].second()
        }.show()
    }

    private fun confirmDelete(feed: FeedEntity) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.feed_delete_title, feed.name))
            .setMessage(if (feed.isLocal) R.string.feed_delete_local_body else R.string.feed_delete_body)
            .setPositiveButton(R.string.delete) { _, _ -> scope.launch { graph.repository.deleteFeed(feed.id) } }
            .setNegativeButton(R.string.cancel, null)
            .show()
        // Start on the harmless choice.
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    // --- Files: storage access only where the guide allows it (AGENTS.md §7) ---

    private var afterPermission: (() -> Unit)? = null

    private fun withStorage(action: () -> Unit) {
        val inAppFolder = graph.importer.inAppFolder() || graph.importer.appFolderFile(LocalCalendar.EXPORT_FILE_NAME)?.isFile == true
        val needsPermission = !inAppFolder && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (!needsPermission) {
            action()
            return
        }
        afterPermission = action
        requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_STORAGE) return
        val action = afterPermission
        afterPermission = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) action?.invoke() else toast(R.string.permission_denied)
    }

    private fun runImport() {
        val importer = graph.importer
        scope.launch {
            val file = withContext(Dispatchers.IO) { importer.find() }
            if (file == null) {
                message(getString(R.string.import_not_found))
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                try {
                    val parsed = FeedImport.parse(importer.read(file))
                    val plan = FeedImport.plan(graph.repository.existingForImport(), parsed)
                    val added = graph.repository.applyImport(plan)
                    Result.success(plan.summary() to added)
                } catch (e: ImportFormatException) {
                    Result.failure(e)
                } catch (e: Exception) {
                    Log.w(TAG, "import failed: ${e.javaClass.simpleName}")
                    Result.failure(Exception(getString(R.string.import_unreadable)))
                }
            }
            result.onSuccess { (summary, added) ->
                if (added.isNotEmpty()) SyncScheduler.syncNow(this@SettingsActivity)
                offerDelete(file, summary)
            }.onFailure { message(it.message ?: getString(R.string.import_unreadable)) }
        }
    }

    private fun offerDelete(file: File, summary: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(summary)
            .setMessage(getString(R.string.import_delete_title) + "\n" + getString(R.string.import_delete_body))
            .setPositiveButton(R.string.delete) { _, _ ->
                toast(if (file.delete()) R.string.import_deleted else R.string.import_not_deleted)
            }
            .setNegativeButton(R.string.keep, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    private fun runLocalImport() {
        scope.launch {
            val file = withContext(Dispatchers.IO) { graph.importer.find(LocalCalendar.EXPORT_FILE_NAME) }
            if (file == null) {
                message(getString(R.string.local_import_not_found))
                return@launch
            }
            try {
                val r = graph.local.importFrom(file)
                message(getString(R.string.local_import_done, r.events.size, r.skipped))
            } catch (e: Exception) {
                Log.w(TAG, "phone calendar import failed: ${e.javaClass.simpleName}")
                message(getString(R.string.local_import_failed))
            }
        }
    }

    private fun runLocalExport() {
        val file = graph.importer.appFolderFile(LocalCalendar.EXPORT_FILE_NAME)
        if (file == null) {
            message(getString(R.string.local_export_failed))
            return
        }
        scope.launch {
            try {
                val n = graph.local.exportTo(file)
                message(getString(R.string.local_export_done, n))
            } catch (e: Exception) {
                Log.w(TAG, "phone calendar export failed: ${e.javaClass.simpleName}")
                message(getString(R.string.local_export_failed))
            }
        }
    }

    private fun message(text: String) {
        AlertDialog.Builder(this).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
    }

    private inner class Adapter : BaseAdapter() {
        var items: List<Item> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun hasStableIds() = true
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = items[position] is Item.Action || items[position] is Item.Feed
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
                is Item.Feed -> {
                    bar.visibility = View.VISIBLE
                    bar.setBackgroundColor(calendarColor(item.feed.color))
                    title.text = item.feed.name
                    title.setTextColor(themeColor(if (item.feed.enabled) R.attr.tcTextPrimary else R.attr.tcTextSecondary))
                    sub.text = item.subtitle
                    sub.visibility = View.VISIBLE
                    val failing = item.feed.enabled && !item.feed.isLocal && item.feed.lastError != null
                    sub.setTextColor(themeColor(if (failing) R.attr.tcError else R.attr.tcTextSecondary))
                }
                else -> Unit
            }
            return v
        }
    }

    companion object {
        const val EXTRA_PAGE = "page"
        private const val TAG = "TurboCalSettings"
        private const val REQUEST_STORAGE = 1
    }
}
