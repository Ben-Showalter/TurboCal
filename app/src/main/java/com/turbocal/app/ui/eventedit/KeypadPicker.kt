package com.turbocal.app.ui.eventedit

import android.app.AlertDialog
import android.app.Dialog
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.turbocal.app.R
import com.turbocal.app.ui.base.FlipBaseActivity
import com.turbocal.core.display.Formats
import com.turbocal.core.input.KeypadEntry
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * Date and time pickers for the event editor: a dialog with the segments in
 * large type, a preview line ("Thu, Nov 5, 2026") and a key hint. Typing
 * numbers, up/down and left/right all work; OK sets, Back cancels.
 */
object KeypadPicker {

    fun pickDate(activity: FlipBaseActivity, title: String, initial: LocalDate, firstDigit: Int?, onPicked: (LocalDate) -> Unit) {
        // The locale's field order, e.g. ['M','d','y'] → "MDY".
        val order = String(DateFormat.getDateFormatOrder(activity)).uppercase().filter { it in "MDY" }
        val entry = KeypadEntry.forDate(initial, if (order.length == 3) order else "MDY")
        val formats = Formats(Locale.getDefault(), DateFormat.is24HourFormat(activity))
        show(activity, title, entry, "/", activity.getString(R.string.picker_hint_date), firstDigit,
            preview = { formats.fullDate(entry.date()) },
            set = { onPicked(entry.date()) })
    }

    fun pickTime(activity: FlipBaseActivity, title: String, initial: LocalTime, firstDigit: Int?, onPicked: (LocalTime) -> Unit) {
        val use24h = DateFormat.is24HourFormat(activity)
        val entry = KeypadEntry.forTime(initial, use24h)
        val hint = activity.getString(if (use24h) R.string.picker_hint_time24 else R.string.picker_hint_time12)
        show(activity, title, entry, ":", hint, firstDigit, preview = { "" }, set = { onPicked(entry.time()) })
    }

    private fun show(
        activity: FlipBaseActivity,
        title: String,
        entry: KeypadEntry,
        separator: String,
        hint: String,
        firstDigit: Int?,
        preview: () -> String,
        set: () -> Unit,
    ) {
        var dialog: Dialog? = null
        val previewView = TextView(activity).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_label))
            setTextColor(activity.themeColor(R.attr.tcAccentText))
        }
        val picker = KeypadPickerView(
            activity, entry, separator,
            onSet = {
                set()
                dialog?.dismiss()
            },
            onCancel = { dialog?.dismiss() },
            onChange = { previewView.text = preview() },
        )
        val hintView = TextView(activity).apply {
            gravity = Gravity.CENTER
            text = hint
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(activity.themeColor(R.attr.tcTextSecondary))
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(picker, LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt())
            addView(previewView)
            addView(hintView)
        }
        firstDigit?.let { entry.type(it) }
        previewView.text = preview()
        dialog = AlertDialog.Builder(activity).setTitle(title).setView(body).create().also {
            it.setOnShowListener { _ -> picker.requestFocus() }
            it.show()
        }
    }
}
