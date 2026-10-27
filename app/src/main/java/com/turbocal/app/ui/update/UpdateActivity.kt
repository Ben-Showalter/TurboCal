package com.turbocal.app.ui.update

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.turbocal.app.R
import com.turbocal.app.ui.base.FlipBaseActivity
import com.turbocal.app.update.UpdateStatus
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Settings > Software update (AGENTS.md §9). One button that is always the
 * next step: Check → Download → Install. Nothing happens without OK.
 */
class UpdateActivity : FlipBaseActivity() {
    private lateinit var action: Button
    private lateinit var message: TextView
    private lateinit var notes: TextView
    private var onAction: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        setTitleText(getString(R.string.title_update))
        setStatusText(getString(R.string.update_installed, graph.updates.currentVersion()))
        action = findViewById(R.id.action)
        message = findViewById(R.id.message)
        notes = findViewById(R.id.notes)
        action.setOnClickListener { onAction() }
        scope.launch { graph.updates.state.collect { render(it) } }
        action.requestFocus()
        if (graph.updates.state.value is UpdateStatus.Idle) scope.launch { graph.updates.checkForUpdate(force = true) }
    }

    override fun onResume() {
        super.onResume()
        // Back from "allow unknown apps": the user presses Install again; nothing auto-starts.
        render(graph.updates.state.value)
    }

    override fun onPrimaryKey() = onAction()

    private fun render(s: UpdateStatus) {
        val check = { scope.launch { graph.updates.checkForUpdate(force = true) }; Unit }
        val download = { scope.launch { graph.updates.download() }; Unit }
        notes.text = when (s) {
            is UpdateStatus.Available -> s.info.notes
            is UpdateStatus.Downloading -> s.info.notes
            is UpdateStatus.Downloaded -> s.info.notes
            else -> ""
        }
        when (s) {
            is UpdateStatus.Idle -> show("", R.string.update_check, check)
            is UpdateStatus.Checking -> show(getString(R.string.update_checking), R.string.update_checking) {}
            is UpdateStatus.UpToDate -> show(getString(R.string.update_up_to_date), R.string.update_check, check)
            is UpdateStatus.NoRelease -> show(getString(R.string.update_no_release), R.string.update_check, check)
            is UpdateStatus.NoAsset -> show(getString(R.string.update_no_asset, s.tag), R.string.update_check, check)
            is UpdateStatus.Available -> {
                val size = mb(s.info.asset.sizeBytes)
                message.text = getString(R.string.update_available, s.info.tag, size)
                setAction(getString(R.string.update_download, size), download)
            }
            is UpdateStatus.Downloading -> show(getString(R.string.update_downloading, s.percent), R.string.update_checking) {}
                .also { action.text = getString(R.string.update_downloading, s.percent) }
            is UpdateStatus.Downloaded -> show(getString(R.string.update_downloaded, s.info.tag), R.string.update_install) {
                graph.updates.install(this)
            }
            is UpdateStatus.Failed -> show(
                getString(R.string.update_failed, s.message),
                R.string.update_retry,
                if (s.info != null) download else check,
            )
        }
    }

    private fun show(text: String, label: Int, onClick: () -> Unit) {
        message.text = text
        setAction(getString(label), onClick)
    }

    private fun setAction(label: String, onClick: () -> Unit) {
        action.text = label
        onAction = onClick
        setSoftkeys(label, getString(R.string.key_select), "")
    }

    private fun mb(bytes: Long): String = if (bytes <= 0) "?" else String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
}
