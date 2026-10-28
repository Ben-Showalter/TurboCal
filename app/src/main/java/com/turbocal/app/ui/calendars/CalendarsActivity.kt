package com.turbocal.app.ui.calendars

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
import com.turbocal.app.sync.SyncScheduler
import com.turbocal.app.ui.base.FlipBaseActivity
import com.turbocal.app.ui.feededit.FeedEditActivity
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
 * Options > Calendars (owner, round 6): every calendar, phone calendar first.
 *  - OK shows or hides the selected calendar. Hidden calendars keep syncing.
 *  - Left soft key: Edit (rename/colour, sync, export, delete).
 *  - Right soft key: Add (owner override of the guide's right = Options):
 *    import feeds from file, type a link, import .ics into the phone calendar.
 */
class CalendarsActivity : FlipBaseActivity() {

    private lateinit var list: ListView
    private val adapter = Adapter()
    private var feeds: List<FeedEntity> = emptyList()
    private var localCount = 0
    private val formats by lazy { Formats(Locale.getDefault(), DateFormat.is24HourFormat(this)) }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        setTitleText(getString(R.string.title_calendars))
        setStatusText(getString(R.string.calendars_hint))
        setSoftkeys(getString(R.string.key_edit), getString(R.string.key_show_hide), getString(R.string.key_add))
        list = findViewById(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> toggle(adapter.feeds[position]) }
        val localCounts = graph.repository.feeds.flatMapLatest { all ->
            val local = all.firstOrNull { it.isLocal }
            if (local == null) flowOf(0) else graph.repository.localEventCount(local.id)
        }
        scope.launch {
            combine(graph.repository.feeds, localCounts) { f, n -> f to n }.collect { (f, n) ->
                feeds = f.sortedWith(compareBy({ !it.isLocal }, { it.name.lowercase() }))
                localCount = n
                render()
            }
        }
        list.requestFocus()
    }

    private fun render() {
        val selectedId = list.selectedItemId.takeIf { list.selectedItemPosition >= 0 }
        adapter.feeds = feeds
        adapter.notifyDataSetChanged()
        val index = selectedId?.let { id -> feeds.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
        if (feeds.isNotEmpty()) list.setSelection(index)
        setStatusText(getString(if (feeds.isEmpty()) R.string.calendars_empty else R.string.calendars_hint))
    }

    private fun selected(): FeedEntity? = feeds.getOrNull(list.selectedItemPosition)

    /** OK: show or hide. Hidden calendars still sync, so showing one again is instant. */
    private fun toggle(feed: FeedEntity) {
        scope.launch { graph.repository.setEnabled(feed.id, !feed.enabled) }
        toast(if (feed.enabled) R.string.toast_hidden else R.string.toast_shown)
    }

    /** Left soft key: Edit menu for the selected calendar. */
    override fun onPrimaryKey() {
        val feed = selected() ?: return
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += getString(R.string.feed_menu_edit) to {
            startActivity(Intent(this, FeedEditActivity::class.java).putExtra(FeedEditActivity.EXTRA_ID, feed.id))
        }
        if (feed.isLocal) {
            actions += getString(R.string.settings_local_export) to { runLocalExport() }
        } else {
            actions += getString(R.string.feed_menu_sync) to {
                SyncScheduler.syncNow(this, feed.id)
                toast(R.string.toast_sync_started)
            }
        }
        actions += getString(R.string.feed_menu_delete) to { confirmDelete(feed) }
        AlertDialog.Builder(this).setTitle(feed.name).setItems(actions.map { it.first }.toTypedArray()) { _, which ->
            actions[which].second()
        }.show()
    }

    /** Right soft key: Add. */
    override fun onOptionsKey() {
        val actions = listOf<Pair<String, () -> Unit>>(
            getString(R.string.settings_import) to { withStorage(::runImport) },
            getString(R.string.calendars_add_link) to { startActivity(Intent(this, FeedEditActivity::class.java)) },
            getString(R.string.settings_local_import) to { withStorage(::runLocalImport) },
        )
        AlertDialog.Builder(this).setTitle(R.string.key_add).setItems(actions.map { it.first }.toTypedArray()) { _, which ->
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
                if (added.isNotEmpty()) SyncScheduler.syncNow(this@CalendarsActivity)
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
        var feeds: List<FeedEntity> = emptyList()
        override fun getCount() = feeds.size
        override fun getItem(position: Int) = feeds[position]
        override fun getItemId(position: Int) = feeds[position].id
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val feed = feeds[position]
            val v = convertView ?: layoutInflater.inflate(R.layout.row_setting, parent, false)
            val bar = v.findViewById<View>(R.id.color_bar)
            bar.visibility = View.VISIBLE
            bar.setBackgroundColor(calendarColor(feed.color))
            v.findViewById<TextView>(R.id.title).apply {
                text = feed.name
                setTextColor(themeColor(if (feed.enabled) R.attr.tcTextPrimary else R.attr.tcTextSecondary))
            }
            val failing = feed.enabled && !feed.isLocal && feed.lastError != null
            v.findViewById<TextView>(R.id.subtitle).apply {
                visibility = View.VISIBLE
                text = status(feed)
                setTextColor(themeColor(if (failing) R.attr.tcError else R.attr.tcTextSecondary))
            }
            return v
        }
    }

    private fun status(feed: FeedEntity): String {
        if (!feed.enabled) return getString(R.string.calendar_hidden)
        val detail = if (feed.isLocal) {
            resources.getQuantityString(R.plurals.settings_local_count, localCount, localCount)
        } else {
            formats.feedStatus(true, feed.lastSuccessAt, feed.lastError, System.currentTimeMillis(), ZoneId.systemDefault())
        }
        return getString(R.string.calendar_shown, detail)
    }

    companion object {
        private const val TAG = "TurboCalCalendars"
        private const val REQUEST_STORAGE = 1
    }
}
