package com.yd1bux.gk2a

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Form "Dari / Sampai" (tanggal + jam, dalam [zone]) plus pilihan interval; dipakai dialog arsip & GIF. */
class RangeForm(
    private val context: Context,
    zone: ZoneId,
    private val first: Instant,
    private val last: Instant,
    initialFrom: Instant,
    initialTo: Instant,
) {
    var from: ZonedDateTime = ZonedDateTime.ofInstant(initialFrom, zone)
        private set
    var to: ZonedDateTime = ZonedDateTime.ofInstant(initialTo, zone)
        private set

    /** Dipanggil setiap kali rentang atau interval berubah. */
    var onChange: () -> Unit = {}

    private val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault()).withZone(zone)
    private val shortDate = DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault()).withZone(zone)
    private val hourFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)

    private val fromDate = Button(context)
    private val fromTime = Button(context)
    private val toDate = Button(context)
    private val toTime = Button(context)
    private val stepGroup = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
    private val stepIds = HashMap<Int, Int>()

    val view: LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    val stepMinutes: Int get() = stepIds[stepGroup.checkedRadioButtonId] ?: 10

    init {
        addRow(R.string.from, fromDate, fromTime)
        addRow(R.string.to, toDate, toTime)
        fromDate.setOnClickListener { pickDate(from) { from = it } }
        fromTime.setOnClickListener { pickTime(from) { from = it } }
        toDate.setOnClickListener { pickDate(to) { to = it } }
        toTime.setOnClickListener { pickTime(to) { to = it } }

        view.addView(label(R.string.step))
        for ((minutes, text) in listOf(10 to R.string.step_10, 30 to R.string.step_30, 60 to R.string.step_60)) {
            val rb = RadioButton(context).apply {
                id = View.generateViewId()
                setText(text)
            }
            stepIds[rb.id] = minutes
            stepGroup.addView(rb)
        }
        stepGroup.check(stepIds.keys.first { stepIds[it] == 10 })
        stepGroup.setOnCheckedChangeListener { _, _ -> onChange() }
        view.addView(stepGroup)
        refresh()
    }

    /** Frame dari [frames] yang masuk rentang dan cocok dengan interval. */
    fun select(frames: List<Frame>): List<Frame> {
        val a = from.toInstant()
        val b = to.toInstant()
        return frames.filter {
            !it.time.isBefore(a) && !it.time.isAfter(b) && (it.time.epochSecond / 60) % stepMinutes == 0L
        }
    }

    fun isValid() = from.toInstant().isBefore(to.toInstant())

    fun label() = "${shortDate.format(from)} ${hourFormat.format(from)} – " +
        "${shortDate.format(to)} ${hourFormat.format(to)} WIB"

    fun label(textRes: Int) = TextView(context).apply {
        setText(textRes)
        setPadding(0, dp(12), 0, 0)
    }

    private fun addRow(text: Int, date: Button, time: Button) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        row.addView(TextView(context).apply { setText(text) }, LinearLayout.LayoutParams(dp(64), -2))
        row.addView(date, LinearLayout.LayoutParams(0, -2, 1.4f))
        row.addView(time, LinearLayout.LayoutParams(0, -2, 1f))
        view.addView(row)
    }

    private fun refresh() {
        fromDate.text = dateFormat.format(from)
        fromTime.text = hourFormat.format(from)
        toDate.text = dateFormat.format(to)
        toTime.text = hourFormat.format(to)
        onChange()
    }

    private fun pickDate(d: ZonedDateTime, set: (ZonedDateTime) -> Unit) {
        DatePickerDialog(context, { _, y, m, day ->
            set(d.withYear(y).withMonth(m + 1).withDayOfMonth(day))
            refresh()
        }, d.year, d.monthValue - 1, d.dayOfMonth).apply {
            datePicker.minDate = first.minus(Duration.ofDays(1)).toEpochMilli()
            datePicker.maxDate = last.plus(Duration.ofDays(1)).toEpochMilli()
        }.show()
    }

    private fun pickTime(d: ZonedDateTime, set: (ZonedDateTime) -> Unit) {
        TimePickerDialog(context, { _, h, m ->
            set(d.withHour(h).withMinute(m / 10 * 10))
            refresh()
        }, d.hour, d.minute, true).show()
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
