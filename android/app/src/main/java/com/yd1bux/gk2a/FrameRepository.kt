package com.yd1bux.gk2a

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class Frame(val time: Instant, val url: String) {
    val fileName: String get() = url.substringAfterLast('/')
}

data class FrameManifest(val updated: Instant?, val frames: List<Frame>)

/** Arsip harian di GitHub Releases: daftar waktu frame yang tersedia + pola URL-nya. */
data class ArchiveIndex(val frames: List<Frame>)

/** Mengambil frames.json, archive.json dan file JPEG, dengan cache di penyimpanan aplikasi. */
class FrameRepository(private val baseUrl: String, cacheRoot: File) {

    private val cacheDir = File(cacheRoot, "frames").apply { mkdirs() }
    private val stampFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

    fun fetchManifest(): FrameManifest {
        val json = getJson("frames.json")
        val arr = json.getJSONArray("frames")
        val frames = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Frame(parseTime(o.getString("time")), o.getString("url"))
        }.sortedBy { it.time }
        val updated = json.optString("updated").takeIf { it.isNotEmpty() }?.let(::parseTime)
        return FrameManifest(updated, frames)
    }

    fun fetchArchive(): ArchiveIndex {
        val json = getJson("archive.json")
        val template = json.getString("url_template")
        val days = json.getJSONObject("days")
        val frames = ArrayList<Frame>()
        for (day in days.keys()) {
            val times = days.getJSONArray(day)
            for (i in 0 until times.length()) {
                val hhmm = times.getString(i)
                val time = LocalDateTime.parse(day + hhmm, stampFormat).toInstant(ZoneOffset.UTC)
                frames += Frame(time, template.replace("{date}", day).replace("{time}", hhmm))
            }
        }
        return ArchiveIndex(frames.sortedBy { it.time })
    }

    /** File lokal frame; diunduh dulu bila belum ada di cache. */
    fun fileFor(frame: Frame): File {
        val file = File(cacheDir, frame.fileName)
        if (!file.exists()) {
            val tmp = File(cacheDir, frame.fileName + ".part")
            val url = if (frame.url.startsWith("http")) frame.url else baseUrl + frame.url
            tmp.writeBytes(get(url))
            if (!tmp.renameTo(file)) throw IOException("Cannot write ${file.name}")
        }
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    /** Batasi ukuran cache: hapus file terlama yang tidak sedang dipakai. */
    fun trimCache(inUse: List<Frame>, maxBytes: Long = MAX_CACHE_BYTES) {
        val keep = inUse.map { it.fileName }.toSet()
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            if (f.name in keep) continue
            total -= f.length()
            f.delete()
        }
    }

    /** Versi kecil (RGB_565) untuk animasi; lebarnya disesuaikan jumlah frame supaya muat di memori. */
    fun decodePreview(file: File, targetWidth: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(file, targetWidth)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    fun decodeFull(file: File): Bitmap? = BitmapFactory.decodeFile(file.path)

    /** Bitmap ARGB dengan ukuran persis [width] x [height] (untuk GIF). */
    fun decodeExact(file: File, width: Int, height: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(file, width) }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        if (bmp.width == width && bmp.height == height) return bmp
        return Bitmap.createScaledBitmap(bmp, width, height, true).also { if (it !== bmp) bmp.recycle() }
    }

    /** Lebar & tinggi asli file gambar. */
    fun dimensions(file: File): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    /** Potongan [region] dari gambar, diskalakan persis ke [width] x [height] (ARGB). */
    @Suppress("DEPRECATION")
    fun decodeRegion(file: File, region: Rect, width: Int, height: Int): Bitmap? {
        val decoder = BitmapRegionDecoder.newInstance(file.path, false) ?: return null
        try {
            var sample = 1
            while (region.width() / (sample * 2) >= width) sample *= 2
            val bmp = decoder.decodeRegion(region, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            if (bmp.width == width && bmp.height == height) return bmp
            return Bitmap.createScaledBitmap(bmp, width, height, true).also { if (it !== bmp) bmp.recycle() }
        } finally {
            decoder.recycle()
        }
    }

    private fun sampleSize(file: File, targetWidth: Int): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        return sample
    }

    private fun getJson(name: String) =
        JSONObject(String(get("$baseUrl$name?t=${System.currentTimeMillis()}"), Charsets.UTF_8))

    private fun get(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.useCaches = false
            conn.instanceFollowRedirects = true
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
        private const val MAX_CACHE_BYTES = 300L * 1024 * 1024
    }
}
