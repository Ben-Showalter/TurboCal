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
import com.turbocal.app.data.FeedEntity
import com.turbocal.app.data.Prefs
import com.turbocal.app.sync.SyncScheduler
import com.turbocal.app.sync.SyncState
import com.turbocal.app.ui.base.CalendarActivity
import com.turbocal.app.ui.base.FlipBaseActivity
import com.turbocal.app.ui.feededit.FeedEditActivity
import com.turbocal.app.ui.update.UpdateActivity
import com.turbocal.app.update.UpdateStatus
import com.turbocal.core.feeds.FeedImport
import com.turbocal.core.feeds.ImportFormatException
import com.turbocal.core.display.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.Locale

/**
 * Feeds (add, import, edit, enable, colour, delete), sync interval, sync now,
 * per-feed status and Software update. One list; OK acts on the selected row.
 */
class SettingsActivity : FlipBaseActivity() {

    private sealed class Item {
        abstract val id: Long
        data class Header(val text: String, override val id: Long) : Item()
        data class Action(val key: String, val title: String, val subtitle: String, override val id: Long) : Item()
        data class Feed(val feed: FeedEntity, val subtitle: String) : Item() {
            override val id get() = 1000 + feed.id
        }
        data class Note(val text: String, override val id: Long) : Item()
    }

    private lateinit var list: ListView
    private val adapter = Adapter()
    private var feeds: List<FeedEntity> = emptyList()
    private var updateStatus: UpdateStatus = UpdateStatus.Idle
    private var syncing = false
    private val formats by lazy { Formats(Locale.getDefault(), DateFormat.is24HourFormat(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        setTitleText(getString(R.string.title_settings))
        setSoftkeys(getString(R.string.key_sync), getString(R.string.key_select), getString(R.string.key_keys))
        list = findViewById(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> onItem(adapter.items[position]) }
        scope.launch {
            combine(graph.repository.feeds, SyncState.running, graph.updates.state, graph.prefs.showDisabled) { f, s, u, _ ->
                Triple(f, s, u)
            }.collect { (f, s, u) ->
                feeds = f
                syncing = s
                updateStatus = u
                render()
            }
        }
        list.requestFocus()
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val items = ArrayList<Item>()
        items += Item.Header(getString(R.string.settings_header_sync), 1)
        val anyError = feeds.any { it.enabled && it.lastError != null }
        items += Item.Action(
            "sync", getString(R.string.settings_sync_now),
            when {
                syncing -> getString(R.string.status_syncing)
                anyError -> getString(R.string.status_error)
                else -> formats.synced(feeds.filter { it.enabled }.mapNotNull { it.lastSuccessAt }.minOrNull(), now, zone)
            },
            2,
        )
        items += Item.Action("interval", getString(R.string.settings_interval), Prefs.intervalLabel(graph.prefs.syncIntervalMinutes), 3)
        items += Item.Header(getString(R.string.settings_header_calendars), 4)
        items += Item.Action("import", getString(R.string.settings_import), getString(R.string.settings_import_sub), 5)
        items += Item.Action("add", getString(R.string.settings_add), "", 6)
        if (feeds.isEmpty()) items += Item.Note(getString(R.string.settings_no_feeds), 7)
        for (f in feeds) items += Item.Feed(f, formats.feedStatus(f.enabled, f.lastSuccessAt, f.lastError, now, zone))
        items += Item.Header(getString(R.string.settings_header_app), 8)
        items += Item.Action(
            "disabled", getString(R.string.settings_show_disabled),
            getString(if (graph.prefs.showDisabled.value) R.string.on else R.string.off), 9,
        )
        val version = graph.updates.currentVersion()
        val badge = (updateStatus as? UpdateStatus.Available)?.let { " · " + getString(R.string.update_badge, it.info.tag) } ?: ""
        items += Item.Action("update", getString(R.string.settings_update), getString(R.string.update_installed, version) + badge, 10)

        val selectedId = list.selectedItemId.takeIf { list.selectedItemPosition >= 0 }
        adapter.items = items
        adapter.notifyDataSetChanged()
        val index = selectedId?.let { id -> items.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 1
        list.setSelection(index)
    }

    private fun onItem(item: Item) {
        when (item) {
            is Item.Action -> when (item.key) {
                "sync" -> CalendarActivity.syncNow(this)
                "interval" -> chooseInterval()
                "import" -> importFeeds()
                "add" -> startActivity(Intent(this, FeedEditActivity::class.java))
                "disabled" -> graph.prefs.setShowDisabled(!graph.prefs.showDisabled.value)
                "update" -> startActivity(Intent(this, UpdateActivity::class.java))
            }
            is Item.Feed -> feedMenu(item.feed)
            else -> Unit
        }
    }

    override fun onPrimaryKey() = CalendarActivity.syncNow(this)

    override fun onOptionsKey() {
        AlertDialog.Builder(this).setMessage(R.string.keys_help).setPositiveButton(android.R.string.ok, null).show()
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
        val items = arrayOf(
            getString(R.string.feed_menu_edit),
            getString(if (feed.enabled) R.string.feed_menu_turn_off else R.string.feed_menu_turn_on),
            getString(R.string.feed_menu_sync),
            getString(R.string.feed_menu_delete),
        )
        AlertDialog.Builder(this).setTitle(feed.name).setItems(items) { _, which ->
            when (which) {
                0 -> startActivity(Intent(this, FeedEditActivity::class.java).putExtra(FeedEditActivity.EXTRA_ID, feed.id))
                1 -> scope.launch {
                    graph.repository.setEnabled(feed.id, !feed.enabled)
                    if (!feed.enabled) SyncScheduler.syncNow(this@SettingsActivity, feed.id)
                }
                2 -> if (feed.enabled) {
                    SyncScheduler.syncNow(this, feed.id)
                    toast(R.string.toast_sync_started)
                }
                3 -> confirmDelete(feed)
            }
        }.show()
    }

    private fun confirmDelete(feed: FeedEntity) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.feed_delete_title, feed.name))
            .setMessage(R.string.feed_delete_body)
            .setPositiveButton(R.string.delete) { _, _ -> scope.launch { graph.repository.deleteFeed(feed.id) } }
            .setNegativeButton(R.string.cancel, null)
            .show()
        // Start on the harmless choice.
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    // --- Import (AGENTS.md §7) ---

    private fun importFeeds() {
        val importer = graph.importer
        val needsPermission = !importer.inAppFolder() && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_STORAGE,
            )
            return
        }
        runImport()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_STORAGE) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) runImport() else toast(R.string.permission_denied)
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
                v.setTextColor(getColor(if (item is Item.Header) R.color.accent else R.color.text_secondary))
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
                    sub.text = item.subtitle
                    sub.visibility = if (item.subtitle.isEmpty()) View.GONE else View.VISIBLE
                    sub.setTextColor(getColor(R.color.text_secondary))
                }
                is Item.Feed -> {
                    bar.visibility = View.VISIBLE
                    bar.setBackgroundColor(item.feed.color)
                    title.text = item.feed.name
                    title.setTextColor(getColor(if (item.feed.enabled) R.color.text_primary else R.color.text_secondary))
                    sub.text = item.subtitle
                    sub.visibility = View.VISIBLE
                    sub.setTextColor(getColor(if (item.feed.enabled && item.feed.lastError != null) R.color.error else R.color.text_secondary))
                }
                else -> Unit
            }
            if (item is Item.Action) title.setTextColor(getColor(R.color.text_primary))
            return v
        }
    }

    companion object {
        private const val TAG = "TurboCalSettings"
        private const val REQUEST_STORAGE = 1
    }
}
