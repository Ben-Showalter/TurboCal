package com.turbocal.app.ui.eventedit

import android.app.AlertDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.turbocal.app.R
import com.turbocal.app.ui.base.FlipBaseActivity
import com.turbocal.core.display.Formats
import com.turbocal.core.local.LocalEvent
import com.turbocal.core.local.RepeatChoice
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/**
 * Add or edit an event in the "On this phone" calendar, keypad only.
 * Date and time rows: OK (or typing a digit) opens a keypad picker
 * (KeypadPicker); LEFT/RIGHT also nudge them by a day or 15 minutes. Repeat
 * cycles with LEFT/RIGHT or OK. Moving the start moves the end with it.
 * Editing a repeating event edits every repeat.
 */
class EventEditActivity : FlipBaseActivity() {
    private val formats by lazy { Formats(Locale.getDefault(), DateFormat.is24HourFormat(this)) }

    private var uid: String? = null
    private var allDay = false
    private var start: LocalDateTime = LocalDateTime.now()
    /** Timed: exclusive end. All-day: the last day (inclusive) at midnight. */
    private var end: LocalDateTime = LocalDateTime.now()
    private var rrule: String? = null
    private var customRule: String? = null
    private var exdates: List<LocalDateTime> = emptyList()

    private lateinit var title: EditText
    private lateinit var location: EditText
    private lateinit var notes: EditText
    private lateinit var allDayRow: TextView
    private lateinit var startDateRow: TextView
    private lateinit var startTimeRow: TextView
    private lateinit var endDateRow: TextView
    private lateinit var endTimeRow: TextView
    private lateinit var repeatRow: TextView
    private lateinit var error: TextView
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_edit)
        setSoftkeys(getString(R.string.key_save), "", "")
        title = findViewById(R.id.title_field)
        location = findViewById(R.id.location_field)
        notes = findViewById(R.id.notes_field)
        allDayRow = findViewById(R.id.all_day)
        startDateRow = findViewById(R.id.start_date)
        startTimeRow = findViewById(R.id.start_time)
        endDateRow = findViewById(R.id.end_date)
        endTimeRow = findViewById(R.id.end_time)
        repeatRow = findViewById(R.id.repeat)
        error = findViewById(R.id.error)
        hint = findViewById(R.id.hint)

        stepper(startDateRow, R.string.hint_date, { moveStart(days = it) }) { digit ->
            KeypadPicker.pickDate(this, getString(R.string.picker_start_date), start.toLocalDate(), digit) { setStart(it.atTime(start.toLocalTime())) }
        }
        stepper(startTimeRow, R.string.hint_time, { moveStart(minutes = it * 15L) }) { digit ->
            KeypadPicker.pickTime(this, getString(R.string.picker_start_time), start.toLocalTime(), digit) { setStart(start.toLocalDate().atTime(it)) }
        }
        stepper(endDateRow, R.string.hint_date, { moveEnd(days = it) }) { digit ->
            KeypadPicker.pickDate(this, getString(R.string.picker_end_date), end.toLocalDate(), digit) { setEnd(it.atTime(end.toLocalTime())) }
        }
        stepper(endTimeRow, R.string.hint_time, { moveEnd(minutes = it * 15L) }) { digit ->
            KeypadPicker.pickTime(this, getString(R.string.picker_end_time), end.toLocalTime(), digit) { setEnd(end.toLocalDate().atTime(it)) }
        }
        stepper(repeatRow, R.string.hint_repeat, { cycleRepeat(it) })
        allDayRow.setOnClickListener { toggleAllDay() }
        allDayRow.onFocusChangeListener = View.OnFocusChangeListener { _, has -> if (has) hint.setText(R.string.hint_toggle) }
        for (f in listOf(title, location, notes)) f.onFocusChangeListener = View.OnFocusChangeListener { _, has -> if (has) hint.text = "" }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
        val deleteButton = findViewById<Button>(R.id.delete)
        deleteButton.setOnClickListener { confirmDelete() }

        val editUid = intent.getStringExtra(EXTRA_UID)
        if (editUid == null) {
            deleteButton.visibility = View.GONE
            setTitleText(getString(R.string.title_new_event))
            val day = intent.getLongExtra(EXTRA_DATE, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
                ?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now()
            start = defaultStart(day)
            end = start.plusHours(1)
            render()
        } else {
            scope.launch {
                val e = graph.local.event(editUid) ?: return@launch finish()
                load(e)
            }
        }
        title.requestFocus()
    }

    private fun defaultStart(day: LocalDate): LocalDateTime {
        val now = LocalDateTime.now()
        return if (day == now.toLocalDate()) now.truncatedTo(ChronoUnit.HOURS).plusHours(1) else day.atTime(9, 0)
    }

    private fun load(e: LocalEvent) {
        uid = e.uid
        title.setText(e.title)
        location.setText(e.location)
        notes.setText(e.description)
        allDay = e.allDay
        start = e.start
        end = if (e.allDay) e.end.minusDays(1) else e.end
        rrule = e.rrule
        customRule = e.rrule?.takeIf { RepeatChoice.of(it) == null }
        exdates = e.exdates
        setTitleText(getString(if (e.rrule != null) R.string.title_edit_repeats else R.string.title_edit_event))
        render()
    }

    private val pickerRows = HashSet<View>()

    /**
     * A row changed from the keypad: LEFT/RIGHT step it; OK opens [pick] (or
     * steps when there is no picker); a digit opens [pick] with that digit
     * already typed.
     */
    private fun stepper(row: TextView, hintRes: Int, step: (Int) -> Unit, pick: ((Int?) -> Unit)? = null) {
        if (pick != null) pickerRows += row
        row.setOnKeyListener { _, keyCode, event ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        step(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                        render()
                    }
                    true
                }
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    if (pick == null) return@setOnKeyListener false
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) pick(keyCode - KeyEvent.KEYCODE_0)
                    true
                }
                else -> false
            }
        }
        row.setOnClickListener {
            if (pick != null) {
                pick(null)
            } else {
                step(1)
                render()
            }
        }
        row.onFocusChangeListener = View.OnFocusChangeListener { _, has -> if (has) hint.setText(hintRes) }
    }

    override fun wantsDigits(): Boolean = currentFocus in pickerRows

    private fun moveStart(days: Int = 0, minutes: Long = 0) = setStart(start.plusDays(days.toLong()).plusMinutes(minutes))

    private fun moveEnd(days: Int = 0, minutes: Long = 0) = setEnd(end.plusDays(days.toLong()).plusMinutes(minutes))

    /** A new start moves the end with it, keeping the event's length. */
    private fun setStart(next: LocalDateTime) {
        val delta = ChronoUnit.MINUTES.between(start, next)
        start = next
        end = end.plusMinutes(delta)
        render()
    }

    private fun setEnd(next: LocalDateTime) {
        end = next
        error.visibility = View.GONE
        render()
    }

    private fun toggleAllDay() {
        allDay = !allDay
        if (allDay) {
            start = start.toLocalDate().atStartOfDay()
            end = maxOf(end.toLocalDate(), start.toLocalDate()).atStartOfDay()
        } else {
            start = start.toLocalDate().atTime(LocalTime.of(9, 0))
            end = start.plusHours(1)
        }
        render()
    }

    private fun repeatOptions(): List<String?> =
        (if (customRule != null) listOf<String?>(customRule) else emptyList()) + RepeatChoice.values().map { it.rrule }

    private fun cycleRepeat(step: Int) {
        val options = repeatOptions()
        val i = options.indexOf(rrule).coerceAtLeast(0)
        rrule = options[Math.floorMod(i + step, options.size)]
    }

    private fun repeatLabel(): String = when {
        rrule != null && rrule == customRule -> getString(R.string.repeat_custom)
        else -> RepeatChoice.of(rrule)?.label ?: getString(R.string.repeat_custom)
    }

    private fun render() {
        val today = LocalDate.now()
        allDayRow.text = getString(if (allDay) R.string.event_all_day_on else R.string.event_all_day_off)
        startDateRow.text = getString(R.string.event_starts, formats.dayTitle(start.toLocalDate(), today))
        endDateRow.text = getString(R.string.event_ends, formats.dayTitle(end.toLocalDate(), today))
        startTimeRow.text = getString(R.string.event_at, formats.time(start.toLocalTime()))
        endTimeRow.text = getString(R.string.event_until, formats.time(end.toLocalTime()))
        startTimeRow.visibility = if (allDay) View.GONE else View.VISIBLE
        endTimeRow.visibility = if (allDay) View.GONE else View.VISIBLE
        repeatRow.text = getString(R.string.event_repeat, repeatLabel())
    }

    override fun onPrimaryKey() = save()

    private fun save() {
        val ok = if (allDay) !end.toLocalDate().isBefore(start.toLocalDate()) else !end.isBefore(start)
        if (!ok) {
            error.setText(R.string.event_end_before_start)
            error.visibility = View.VISIBLE
            endDateRow.requestFocus()
            return
        }
        val event = LocalEvent(
            uid = uid ?: UUID.randomUUID().toString(),
            title = title.text.toString().trim(),
            location = location.text.toString().trim(),
            description = notes.text.toString().trim(),
            allDay = allDay,
            start = if (allDay) start.toLocalDate().atStartOfDay() else start,
            end = if (allDay) end.toLocalDate().plusDays(1).atStartOfDay() else end,
            rrule = rrule,
            // Kept as is: an exclusion only matches an occurrence that still starts at that time.
            exdates = exdates,
        )
        scope.launch {
            graph.local.save(event)
            finish()
        }
    }

    private fun confirmDelete() {
        val id = uid ?: return
        val dialog = AlertDialog.Builder(this)
            .setMessage(R.string.event_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                scope.launch {
                    graph.local.delete(id)
                    finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    companion object {
        const val EXTRA_DATE = "date"
        const val EXTRA_UID = "uid"
    }
}
