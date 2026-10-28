package com.yd1bux.gk2a

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime

data class Frame(val time: Instant, val url: String) {
    val fileName: String get() = url.substringAfterLast('/')
}

data class FrameManifest(val updated: Instant?, val frames: List<Frame>)

/** Mengambil frames.json dan file JPEG dari situs GitHub Pages, dengan cache di penyimpanan aplikasi. */
class FrameRepository(private val baseUrl: String, cacheRoot: File) {

    private val cacheDir = File(cacheRoot, "frames").apply { mkdirs() }

    fun fetchManifest(): FrameManifest {
        val json = JSONObject(String(get("${baseUrl}frames.json?t=${System.currentTimeMillis()}"), Charsets.UTF_8))
        val arr = json.getJSONArray("frames")
        val frames = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Frame(parseTime(o.getString("time")), o.getString("url"))
        }.sortedBy { it.time }
        val updated = json.optString("updated").takeIf { it.isNotEmpty() }?.let(::parseTime)
        return FrameManifest(updated, frames)
    }

    /** File lokal frame; diunduh dulu bila belum ada di cache. */
    fun fileFor(frame: Frame): File {
        val file = File(cacheDir, frame.fileName)
        if (!file.exists()) {
            val tmp = File(cacheDir, frame.fileName + ".part")
            tmp.writeBytes(get(baseUrl + frame.url))
            if (!tmp.renameTo(file)) throw IOException("Cannot write ${file.name}")
        }
        return file
    }

    /** Hapus frame lama yang sudah tidak ada di manifest. */
    fun prune(frames: List<Frame>) {
        val keep = frames.map { it.fileName }.toSet()
        cacheDir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    /** Versi kecil (RGB_565, lebar ~1200 px) untuk animasi, supaya 18 frame muat di memori. */
    fun decodePreview(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= PREVIEW_WIDTH) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    fun decodeFull(file: File): Bitmap? = BitmapFactory.decodeFile(file.path)

    private fun get(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.useCaches = false
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode} for $url")
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseTime(s: String): Instant = OffsetDateTime.parse(s).toInstant()

    companion object {
        private const val PREVIEW_WIDTH = 1200
    }
}
