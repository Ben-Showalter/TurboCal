package com.turbocal.app.ui.feededit

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.turbocal.app.R
import com.turbocal.app.sync.SyncScheduler
import com.turbocal.app.ui.base.FlipBaseActivity
import com.turbocal.core.feeds.FeedColors
import com.turbocal.core.feeds.FeedUrl
import kotlinx.coroutines.launch

/**
 * Add or edit one feed by typing (AGENTS.md §7). Name and link are plain
 * EditTexts using the phone's T9; colour cycles with LEFT/RIGHT; Save and
 * Cancel are D-pad rows because the T9 may take the soft keys.
 */
class FeedEditActivity : FlipBaseActivity() {
    private var feedId: Long? = null
    /** The phone calendar: name, colour and on/off only; it has no link. */
    private var isLocal = false
    private var color = FeedColors.PALETTE[0]
    private var enabled = true

    private lateinit var name: EditText
    private lateinit var url: EditText
    private lateinit var error: TextView
    private lateinit var colorRow: View
    private lateinit var colorSwatch: View
    private lateinit var colorLabel: TextView
    private lateinit var enabledRow: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feed_edit)
        setSoftkeys(getString(R.string.key_save), "", "")
        name = findViewById(R.id.name)
        url = findViewById(R.id.url)
        error = findViewById(R.id.error)
        colorRow = findViewById(R.id.color_row)
        colorSwatch = findViewById(R.id.color_swatch)
        colorLabel = findViewById(R.id.color_label)
        enabledRow = findViewById(R.id.enabled)

        colorRow.setOnKeyListener { _, keyCode, event ->
            val step = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> return@setOnKeyListener false
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                color = FeedColors.step(color, step)
                renderToggles()
            }
            true
        }
        colorRow.setOnClickListener {
            color = FeedColors.step(color, 1)
            renderToggles()
        }
        enabledRow.setOnClickListener {
            enabled = !enabled
            renderToggles()
        }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }

        val id = intent.getLongExtra(EXTRA_ID, -1).takeIf { it >= 0 }
        feedId = id
        setTitleText(getString(if (id == null) R.string.title_add_feed else R.string.title_edit_feed))
        if (id == null) {
            scope.launch {
                color = FeedColors.forIndex(graph.database.dao().allFeeds().size)
                renderToggles()
            }
        } else {
            scope.launch {
                val f = graph.repository.feed(id) ?: return@launch finish()
                isLocal = f.isLocal
                if (isLocal) {
                    findViewById<View>(R.id.url_label).visibility = View.GONE
                    url.visibility = View.GONE
                }
                name.setText(f.name)
                url.setText(f.url)
                color = f.color
                enabled = f.enabled
                renderToggles()
            }
        }
        renderToggles()
        name.requestFocus()
    }

    private fun renderToggles() {
        colorSwatch.setBackgroundColor(color)
        colorLabel.text = getString(R.string.feed_color, FeedColors.nameOf(color))
        enabledRow.setText(if (enabled) R.string.feed_enabled_on else R.string.feed_enabled_off)
    }

    override fun onPrimaryKey() = save()

    private fun save() {
        val normalized = if (isLocal) "" else FeedUrl.normalize(url.text.toString())
        if (normalized == null) {
            error.setText(R.string.feed_bad_url)
            error.visibility = View.VISIBLE
            url.requestFocus()
            return
        }
        val label = name.text.toString().trim().ifEmpty { getString(R.string.feed_name_default) }
        scope.launch {
            val id = feedId
            val savedId = if (id == null) {
                graph.repository.addFeed(label, normalized, color, enabled)
            } else {
                graph.repository.saveFeed(id, label, normalized, color, enabled)
                id
            }
            if (enabled && !isLocal) SyncScheduler.syncNow(this@FeedEditActivity, savedId)
            finish()
        }
    }

    companion object {
        const val EXTRA_ID = "feedId"
    }
}
