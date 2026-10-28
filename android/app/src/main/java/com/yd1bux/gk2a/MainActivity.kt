package com.yd1bux.gk2a

import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    /** Live = 3 jam terakhir dari frames.json; Archive = rentang pilihan dari archive.json. */
    private sealed class Mode {
        object Live : Mode()
        class Archive(val frames: List<Frame>, val label: String) : Mode()
    }

    private lateinit var repo: FrameRepository
    private lateinit var image: ZoomImageView
    private lateinit var status: TextView
    private lateinit var timeText: TextView
    private lateinit var seek: SeekBar
    private lateinit var playButton: ImageButton
    private lateinit var speedButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var errorBox: LinearLayout

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val decoder = Executors.newSingleThreadExecutor()
    private val worker = Executors.newSingleThreadExecutor()

    private var mode: Mode = Mode.Live
    @Volatile private var generation = 0
    private var frames: List<Frame> = emptyList()
    private val previews = HashMap<String, Bitmap>()
    private var manifestUpdated: Instant? = null
    private var index = 0
    private var playing = true
    private var speedIndex = 1
    private var loading = false
    private var exporting = false
    private var fullResIndex = -1
    private var backToLive: BackToLive? = null

    private val wib = ZoneId.of("Asia/Jakarta")
    private val dayFormat = DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault()).withZone(wib)
    private val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault()).withZone(wib)
    private val hourFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(wib)
    private val utcFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)
    private val stampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC)

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            val ready = readyIndices()
            if (ready.size > 1) {
                val pos = ready.indexOf(index)
                show(ready[(pos + 1) % ready.size])
            }
            val delay = if (index == ready.lastOrNull()) LAST_FRAME_PAUSE_MS else SPEEDS[speedIndex].second
            main.postDelayed(this, delay)
        }
    }

    private val autoRefresh = object : Runnable {
        override fun run() {
            if (mode is Mode.Live) load(showSpinner = false)
            main.postDelayed(this, AUTO_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = FrameRepository(getString(R.string.data_base_url), cacheDir)

        image = findViewById(R.id.image)
        status = findViewById(R.id.status)
        timeText = findViewById(R.id.time)
        seek = findViewById(R.id.seek)
        playButton = findViewById(R.id.play)
        speedButton = findViewById(R.id.speed)
        progress = findViewById(R.id.progress)
        errorBox = findViewById(R.id.errorBox)

        applyInsets(findViewById(R.id.root))
        if (Build.VERSION.SDK_INT >= 33) backToLive = BackToLive(this) { setMode(Mode.Live) }

        playButton.setOnClickListener { setPlaying(!playing) }
        findViewById<ImageButton>(R.id.prev).setOnClickListener { step(-1) }
        findViewById<ImageButton>(R.id.next).setOnClickListener { step(1) }
        findViewById<ImageButton>(R.id.refresh).setOnClickListener { load(showSpinner = true) }
        findViewById<ImageButton>(R.id.archive).setOnClickListener { openArchive() }
        findViewById<Button>(R.id.retry).setOnClickListener { load(showSpinner = true) }
        status.setOnClickListener { if (mode is Mode.Archive) setMode(Mode.Live) }
        speedButton.setOnClickListener {
            speedIndex = (speedIndex + 1) % SPEEDS.size
            speedButton.text = SPEEDS[speedIndex].first
        }
        speedButton.text = SPEEDS[speedIndex].first

        // Simpan ke galeri lewat MediaStore tanpa izin penyimpanan hanya ada di Android 10+.
        val save = findViewById<ImageButton>(R.id.save)
        val gif = findViewById<ImageButton>(R.id.gif)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            save.setOnClickListener { saveCurrent() }
            gif.setOnClickListener { exportGif() }
        } else {
            save.visibility = View.GONE
            gif.visibility = View.GONE
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) show(value)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = setPlaying(false)
            override fun onStopTrackingTouch(bar: SeekBar) = loadFullRes()
        })
    }

    override fun onResume() {
        super.onResume()
        main.post(autoRefresh)
        if (playing) startTicking()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(autoRefresh)
        main.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.shutdownNow()
        decoder.shutdownNow()
        worker.shutdownNow()
    }

    @Deprecated("Dipakai hanya di Android < 13; versi baru memakai OnBackInvokedCallback")
    override fun onBackPressed() {
        if (mode is Mode.Archive) setMode(Mode.Live) else super.onBackPressed()
    }

    private fun applyInsets(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun setMode(m: Mode) {
        generation++
        mode = m
        loading = false
        frames = emptyList()
        previews.clear()
        manifestUpdated = null
        index = 0
        fullResIndex = -1
        image.setImageBitmap(null)
        timeText.text = ""
        seek.max = 0
        backToLive?.set(m is Mode.Archive)
        setPlaying(true)
        load(showSpinner = true)
    }

    /** Ambil daftar frame, unduh yang belum ada (terbaru dulu), tampilkan begitu siap. */
    private fun load(showSpinner: Boolean) {
        if (loading) return
        loading = true
        val gen = generation
        val current = mode
        if (showSpinner || frames.isEmpty()) {
            progress.visibility = View.VISIBLE
            errorBox.visibility = View.GONE
            status.setText(R.string.loading_manifest)
        }
        // Salinan untuk thread latar belakang; previews hanya diubah di thread UI.
        val known = previews.keys.toSet()
        val lastUpdated = manifestUpdated
        loader.execute {
            try {
                val manifest = when (current) {
                    is Mode.Live -> repo.fetchManifest()
                    is Mode.Archive -> FrameManifest(null, current.frames)
                }
                if (gen != generation) return@execute
                if (current is Mode.Live && manifest.updated != null && manifest.updated == lastUpdated &&
                    manifest.frames.all { it.url in known }
                ) {
                    main.post { if (gen == generation) finishLoading() }
                    return@execute
                }
                main.post { if (gen == generation) applyManifest(manifest) }
                val width = previewWidth(manifest.frames.size)
                val todo = manifest.frames.filter { it.url !in known }.reversed()
                for ((n, frame) in todo.withIndex()) {
                    if (gen != generation) return@execute
                    val bmp = try {
                        repo.decodePreview(repo.fileFor(frame), width)
                    } catch (e: Exception) {
                        null // satu frame gagal (mis. belum ada di arsip) tidak menghentikan yang lain
                    }
                    main.post {
                        if (gen != generation) return@post
                        if (bmp != null) previews[frame.url] = bmp
                        status.text = getString(R.string.loading_frames, n + 1, todo.size)
                        onFrameReady(frame)
                    }
                }
                repo.trimCache(manifest.frames)
                main.post { if (gen == generation) finishLoading() }
            } catch (e: Exception) {
                main.post {
                    if (gen != generation) return@post
                    loading = false
                    progress.visibility = View.GONE
                    if (previews.isEmpty()) {
                        errorBox.visibility = View.VISIBLE
                        status.setText(R.string.subtitle)
                    } else {
                        Toast.makeText(this, R.string.error_network, Toast.LENGTH_SHORT).show()
                        updateStatus()
                    }
                }
            }
        }
    }

    private fun previewWidth(count: Int) = when {
        count <= 36 -> 1200
        count <= 72 -> 800
        else -> 640
    }

    private fun applyManifest(manifest: FrameManifest) {
        val wasAtEnd = frames.isEmpty() || index >= frames.size - 1
        val currentUrl = frames.getOrNull(index)?.url
        frames = manifest.frames
        manifestUpdated = manifest.updated
        previews.keys.retainAll(frames.map { it.url }.toSet())
        seek.max = (frames.size - 1).coerceAtLeast(0)
        index = if (wasAtEnd) frames.size - 1
        else frames.indexOfFirst { it.url == currentUrl }.takeIf { it >= 0 } ?: (frames.size - 1)
        fullResIndex = -1
        if (previews[frames.getOrNull(index)?.url] != null) show(index)
    }

    private fun onFrameReady(frame: Frame) {
        val i = frames.indexOf(frame)
        if (i < 0 || !previews.containsKey(frame.url)) return
        // Frame pertama yang siap (terbaru) langsung ditampilkan.
        if (image.drawable == null || i == index) {
            progress.visibility = View.GONE
            show(i)
        }
    }

    private fun finishLoading() {
        loading = false
        progress.visibility = View.GONE
        if (previews.isEmpty()) {
            errorBox.visibility = View.VISIBLE
            status.setText(R.string.subtitle)
            return
        }
        updateStatus()
        if (playing) startTicking() else loadFullRes()
    }

    private fun updateStatus() {
        if (exporting) return
        status.text = when (val m = mode) {
            is Mode.Live -> {
                val updated = manifestUpdated ?: frames.lastOrNull()?.time ?: return
                getString(R.string.status_ready, frames.size, "${hourFormat.format(updated)} WIB")
            }
            is Mode.Archive -> getString(R.string.status_archive, m.label, previews.size)
        }
    }

    private fun readyIndices() = frames.indices.filter { previews.containsKey(frames[it].url) }

    private fun show(i: Int) {
        val frame = frames.getOrNull(i) ?: return
        val bmp = previews[frame.url] ?: return
        index = i
        fullResIndex = -1
        image.setImageBitmap(bmp)
        seek.progress = i
        timeText.text = "${dayFormat.format(frame.time)}  ${hourFormat.format(frame.time)} WIB  ·  " +
            "${utcFormat.format(frame.time)} UTC"
    }

    private fun step(delta: Int) {
        setPlaying(false)
        val ready = readyIndices()
        if (ready.isEmpty()) return
        val pos = ready.indexOf(index).takeIf { it >= 0 } ?: (ready.size - 1)
        show(ready[(pos + delta + ready.size) % ready.size])
        loadFullRes()
    }

    private fun setPlaying(p: Boolean) {
        playing = p
        playButton.setImageResource(if (p) R.drawable.ic_pause else R.drawable.ic_play)
        playButton.contentDescription = getString(if (p) R.string.pause else R.string.play)
        main.removeCallbacks(tick)
        if (p) startTicking() else loadFullRes()
    }

    private fun startTicking() {
        main.removeCallbacks(tick)
        main.postDelayed(tick, SPEEDS[speedIndex].second)
    }

    /** Saat dijeda, ganti frame yang tampil dengan versi resolusi penuh supaya zoom tetap tajam. */
    private fun loadFullRes() {
        val frame = frames.getOrNull(index) ?: return
        if (playing || fullResIndex == index) return
        val target = index
        decoder.execute {
            val bmp = try {
                repo.decodeFull(repo.fileFor(frame))
            } catch (e: Exception) {
                null
            }
            main.post {
                if (bmp != null && !playing && index == target && frames.getOrNull(target) == frame) {
                    image.setImageBitmap(bmp)
                    fullResIndex = target
                }
            }
        }
    }

    // ------------------------------------------------------------------ arsip

    private fun openArchive() {
        Toast.makeText(this, R.string.loading_archive, Toast.LENGTH_SHORT).show()
        worker.execute {
            val index = try {
                repo.fetchArchive()
            } catch (e: Exception) {
                null
            }
            main.post {
                if (index == null || index.frames.isEmpty()) {
                    Toast.makeText(this, R.string.archive_unavailable, Toast.LENGTH_LONG).show()
                } else {
                    showArchiveDialog(index)
                }
            }
        }
    }

    private fun showArchiveDialog(archive: ArchiveIndex) {
        val first = archive.frames.first().time
        val last = archive.frames.last().time
        var from = ZonedDateTime.ofInstant(maxOf(first, last.minus(Duration.ofHours(6))), wib)
        var to = ZonedDateTime.ofInstant(last, wib)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.archive_hint, dateFormat.format(first), dateFormat.format(last))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })

        fun row(label: Int): Pair<Button, Button> {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            line.addView(TextView(this).apply { setText(label) }, LinearLayout.LayoutParams(dp(64), -2))
            val date = Button(this)
            val time = Button(this)
            line.addView(date, LinearLayout.LayoutParams(0, -2, 1.4f))
            line.addView(time, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(line)
            return date to time
        }

        val (fromDate, fromTime) = row(R.string.from)
        val (toDate, toTime) = row(R.string.to)
        fun refresh() {
            fromDate.text = dateFormat.format(from)
            fromTime.text = hourFormat.format(from)
            toDate.text = dateFormat.format(to)
            toTime.text = hourFormat.format(to)
        }
        refresh()

        fun pickDate(get: () -> ZonedDateTime, set: (ZonedDateTime) -> Unit) {
            val d = get()
            DatePickerDialog(this, { _, y, m, day ->
                set(d.withYear(y).withMonth(m + 1).withDayOfMonth(day))
                refresh()
            }, d.year, d.monthValue - 1, d.dayOfMonth).apply {
                datePicker.minDate = first.minus(Duration.ofDays(1)).toEpochMilli()
                datePicker.maxDate = last.plus(Duration.ofDays(1)).toEpochMilli()
            }.show()
        }

        fun pickTime(get: () -> ZonedDateTime, set: (ZonedDateTime) -> Unit) {
            val d = get()
            TimePickerDialog(this, { _, h, m ->
                set(d.withHour(h).withMinute(m / 10 * 10))
                refresh()
            }, d.hour, d.minute, true).show()
        }

        fromDate.setOnClickListener { pickDate({ from }, { from = it }) }
        fromTime.setOnClickListener { pickTime({ from }, { from = it }) }
        toDate.setOnClickListener { pickDate({ to }, { to = it }) }
        toTime.setOnClickListener { pickTime({ to }, { to = it }) }

        box.addView(TextView(this).apply {
            setText(R.string.step)
            setPadding(0, dp(12), 0, 0)
        })
        val steps = listOf(10 to R.string.step_10, 30 to R.string.step_30, 60 to R.string.step_60)
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val stepIds = steps.map { (minutes, label) ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                setText(label)
            }
            group.addView(rb)
            rb.id to minutes
        }.toMap()
        group.check(stepIds.keys.first())
        box.addView(group)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.archive_title)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.show, null)
            .setNeutralButton(R.string.live) { _, _ -> setMode(Mode.Live) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = from.toInstant()
                val b = to.toInstant()
                val stepMinutes = stepIds[group.checkedRadioButtonId] ?: 10
                val selected = archive.frames.filter {
                    !it.time.isBefore(a) && !it.time.isAfter(b) && (it.time.epochSecond / 60) % stepMinutes == 0L
                }
                when {
                    !a.isBefore(b) -> toast(R.string.range_invalid)
                    selected.isEmpty() -> toast(R.string.archive_empty)
                    selected.size > MAX_ARCHIVE_FRAMES ->
                        toast(getString(R.string.archive_too_many, selected.size, MAX_ARCHIVE_FRAMES))
                    else -> {
                        dialog.dismiss()
                        val label = "${dayFormat.format(a)} ${hourFormat.format(a)} – " +
                            "${dayFormat.format(b)} ${hourFormat.format(b)} WIB"
                        setMode(Mode.Archive(selected, label))
                    }
                }
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------------ simpan & GIF

    @TargetApi(Build.VERSION_CODES.Q)
    private fun saveCurrent() {
        val frame = frames.getOrNull(index) ?: return
        worker.execute {
            val ok = try {
                val file = repo.fileFor(frame)
                val uri = insertImage("YD1BUX-${file.name}", "image/jpeg")
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IllegalStateException("no stream")
                publish(uri)
                true
            } catch (e: Exception) {
                false
            }
            main.post { toast(if (ok) R.string.saved else R.string.save_failed) }
        }
    }

    /** Buat GIF dari frame yang sedang dimuat (mode live atau rentang arsip), simpan ke galeri. */
    @TargetApi(Build.VERSION_CODES.Q)
    private fun exportGif() {
        if (exporting) return
        val list = readyIndices().map { frames[it] }
        if (list.size < 2) {
            toast(R.string.gif_need_frames)
            return
        }
        exporting = true
        setPlaying(false)
        val delayCs = (SPEEDS[speedIndex].second / 10).toInt()
        val name = "YD1BUX-gk2a_${stampFormat.format(list.first().time)}_${stampFormat.format(list.last().time)}.gif"
        status.text = getString(R.string.gif_progress, 0, list.size)
        worker.execute {
            var uri: Uri? = null
            try {
                val width = if (list.size > 48) 640 else 800
                val first = repo.decodePreview(repo.fileFor(list[0]), width)
                    ?: throw IllegalStateException("decode failed")
                val height = first.height * width / first.width
                first.recycle()
                // Palet dari contoh piksel semua frame (versi kecil), lalu encode frame satu per satu.
                val sw = 200
                val sh = height * sw / width
                val samples = list.mapNotNull { f ->
                    repo.decodeExact(repo.fileFor(f), sw, sh)?.let { b ->
                        IntArray(sw * sh).also { b.getPixels(it, 0, sw, 0, 0, sw, sh); b.recycle() }
                    }
                }
                val palette = Palette.build(samples)
                uri = insertImage(name, "image/gif")
                val out = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("no stream")
                out.buffered().use { stream ->
                    val enc = GifEncoder(stream, width, height, palette)
                    enc.start()
                    val px = IntArray(width * height)
                    list.forEachIndexed { i, f ->
                        val bmp = repo.decodeExact(repo.fileFor(f), width, height) ?: return@forEachIndexed
                        bmp.getPixels(px, 0, width, 0, 0, width, height)
                        bmp.recycle()
                        enc.addFrame(px, if (i == list.lastIndex) LAST_FRAME_PAUSE_MS.toInt() / 10 else delayCs)
                        main.post { status.text = getString(R.string.gif_progress, i + 1, list.size) }
                    }
                    enc.finish()
                }
                publish(uri)
                val saved = uri
                main.post {
                    exporting = false
                    updateStatus()
                    showGifSaved(saved)
                }
            } catch (e: Exception) {
                uri?.let { runCatching { contentResolver.delete(it, null, null) } }
                main.post {
                    exporting = false
                    updateStatus()
                    toast(R.string.gif_failed)
                }
            }
        }
    }

    private fun showGifSaved(uri: Uri) {
        AlertDialog.Builder(this)
            .setMessage(R.string.gif_saved)
            .setPositiveButton(R.string.share) { _, _ ->
                val send = Intent(Intent.ACTION_SEND)
                    .setType("image/gif")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, getString(R.string.share)))
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun insertImage(name: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/YD1BUX-gk2a")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("insert failed")
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun publish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        contentResolver.update(uri, values, null, null)
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Label dan jeda antar frame (ms). */
        private val SPEEDS = listOf("0.5×" to 400L, "1×" to 200L, "2×" to 100L)
        private const val LAST_FRAME_PAUSE_MS = 1500L
        private const val AUTO_REFRESH_MS = 5 * 60 * 1000L
        private const val MAX_ARCHIVE_FRAMES = 144
    }
}

/** Tombol/gestur kembali di Android 13+ (predictive back): dari mode arsip kembali ke live. */
@TargetApi(33)
private class BackToLive(private val activity: Activity, onBack: () -> Unit) {
    private val callback = OnBackInvokedCallback { onBack() }
    private var registered = false

    fun set(enabled: Boolean) {
        val dispatcher = activity.onBackInvokedDispatcher
        if (enabled && !registered) {
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            registered = true
        } else if (!enabled && registered) {
            dispatcher.unregisterOnBackInvokedCallback(callback)
            registered = false
        }
    }
}
