package com.yd1bux.gk2a

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var repo: FrameRepository
    private lateinit var image: ZoomImageView
    private lateinit var status: TextView
    private lateinit var timeText: TextView
    private lateinit var seek: SeekBar
    private lateinit var playButton: ImageButton
    private lateinit var speedButton: Button
    private lateinit var saveButton: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var errorBox: LinearLayout

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val decoder = Executors.newSingleThreadExecutor()

    private var frames: List<Frame> = emptyList()
    private val previews = HashMap<String, Bitmap>()
    private var manifestUpdated: Instant? = null
    private var index = 0
    private var playing = true
    private var speedIndex = 1
    private var loading = false
    private var fullResIndex = -1

    private val wib = ZoneId.of("Asia/Jakarta")
    private val dayFormat = DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault()).withZone(wib)
    private val hourFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(wib)
    private val utcFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)

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
            load(showSpinner = false)
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
        saveButton = findViewById(R.id.save)
        progress = findViewById(R.id.progress)
        errorBox = findViewById(R.id.errorBox)

        applyInsets(findViewById(R.id.root))

        playButton.setOnClickListener { setPlaying(!playing) }
        findViewById<ImageButton>(R.id.prev).setOnClickListener { step(-1) }
        findViewById<ImageButton>(R.id.next).setOnClickListener { step(1) }
        findViewById<ImageButton>(R.id.refresh).setOnClickListener { load(showSpinner = true) }
        findViewById<Button>(R.id.retry).setOnClickListener { load(showSpinner = true) }
        speedButton.setOnClickListener {
            speedIndex = (speedIndex + 1) % SPEEDS.size
            speedButton.text = SPEEDS[speedIndex].first
        }
        speedButton.text = SPEEDS[speedIndex].first

        // MediaStore tanpa izin penyimpanan hanya tersedia di Android 10+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveButton.setOnClickListener { saveCurrent() }
        } else {
            saveButton.visibility = View.GONE
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

    /** Ambil frames.json, unduh frame yang belum ada (terbaru dulu), tampilkan begitu siap. */
    private fun load(showSpinner: Boolean) {
        if (loading) return
        loading = true
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
                val manifest = repo.fetchManifest()
                if (manifest.updated != null && manifest.updated == lastUpdated &&
                    manifest.frames.all { it.url in known }
                ) {
                    main.post { finishLoading() }
                    return@execute
                }
                main.post { applyManifest(manifest) }
                val todo = manifest.frames.filter { it.url !in known }.reversed()
                todo.forEachIndexed { n, frame ->
                    val bmp = repo.decodePreview(repo.fileFor(frame))
                    main.post {
                        if (bmp != null) previews[frame.url] = bmp
                        status.text = getString(R.string.loading_frames, n + 1, todo.size)
                        onFrameReady(frame)
                    }
                }
                repo.prune(manifest.frames)
                main.post { finishLoading() }
            } catch (e: Exception) {
                main.post {
                    loading = false
                    progress.visibility = View.GONE
                    if (frames.isEmpty() || previews.isEmpty()) {
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
        if (i < 0) return
        // Frame pertama yang siap (terbaru) langsung ditampilkan.
        if (image.drawable == null || i == index) {
            progress.visibility = View.GONE
            show(i)
        }
    }

    private fun finishLoading() {
        loading = false
        progress.visibility = View.GONE
        updateStatus()
        if (playing) startTicking() else loadFullRes()
    }

    private fun updateStatus() {
        val updated = manifestUpdated ?: frames.lastOrNull()?.time ?: return
        status.text = getString(R.string.status_ready, frames.size,
            "${hourFormat.format(updated)} WIB")
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
                if (bmp != null && !playing && index == target) {
                    image.setImageBitmap(bmp)
                    fullResIndex = target
                }
            }
        }
    }

    private fun saveCurrent() {
        val frame = frames.getOrNull(index) ?: return
        loader.execute {
            val ok = try {
                val file = repo.fileFor(frame)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "YD1BUX-${file.name}")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/YD1BUX-gk2a")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("insert failed")
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IllegalStateException("no stream")
                true
            } catch (e: Exception) {
                false
            }
            main.post {
                Toast.makeText(this, if (ok) R.string.saved else R.string.save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        /** Label dan jeda antar frame (ms). */
        private val SPEEDS = listOf("0.5×" to 400L, "1×" to 200L, "2×" to 100L)
        private const val LAST_FRAME_PAUSE_MS = 1500L
        private const val AUTO_REFRESH_MS = 5 * 60 * 1000L
    }
}
