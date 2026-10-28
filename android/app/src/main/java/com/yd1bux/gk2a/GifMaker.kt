package com.yd1bux.gk2a

import android.graphics.Rect
import android.graphics.RectF
import java.io.OutputStream

/** Pengaturan GIF dari dialog "Buat GIF". */
data class GifOptions(
    val width: Int,
    /** Jeda antar frame dalam seperseratus detik. */
    val delayCs: Int,
    /** Sisipkan 2 frame crossfade di antara tiap frame supaya gerakan awan lebih halus. */
    val smooth: Boolean,
    /** Putar maju lalu mundur (bolak-balik). */
    val bounce: Boolean,
    /** Tahan 1,5 detik di frame terbaru. */
    val holdEnd: Boolean,
    /** Potong ke bagian gambar ini (0..1), null = seluruh wilayah. */
    val crop: RectF?,
)

/** Menyusun GIF dari daftar frame: unduh, potong/skala, palet bersama, crossfade, bolak-balik. */
class GifMaker(private val repo: FrameRepository) {

    enum class Phase { DOWNLOAD, ENCODE }

    /** Jumlah frame GIF yang akan dihasilkan dari [count] frame sumber. */
    fun outputFrames(count: Int, o: GifOptions) = GifSequence.outputFrames(count, o.smooth, o.bounce)

    /** Lama satu putaran animasi dalam detik. */
    fun durationSeconds(count: Int, o: GifOptions): Double {
        val n = outputFrames(count, o)
        val hold = if (o.holdEnd) GifSequence.HOLD_CS - o.delayCs else 0
        return (n * o.delayCs + hold) / 100.0
    }

    fun make(frames: List<Frame>, o: GifOptions, out: OutputStream, progress: (Phase, Int, Int) -> Unit) {
        // Frame yang hitam (rusak) dilewati supaya tidak mengganggu animasi.
        val files = frames.mapIndexed { i, f ->
            progress(Phase.DOWNLOAD, i + 1, frames.size)
            repo.fileFor(f)
        }.filterNot { repo.isBlank(it) }
        if (files.size < 2) throw IllegalStateException("not enough frames")
        val (fw, fh) = repo.dimensions(files[0])
        val c = o.crop ?: RectF(0f, 0f, 1f, 1f)
        val region = Rect(
            (c.left * fw).toInt().coerceIn(0, fw - 2), (c.top * fh).toInt().coerceIn(0, fh - 2),
            (c.right * fw).toInt().coerceIn(2, fw), (c.bottom * fh).toInt().coerceIn(2, fh),
        )
        val width = o.width.coerceAtMost(region.width() * 2)
        val height = (region.height().toLong() * width / region.width()).toInt().coerceAtLeast(2)

        // Palet dari contoh kecil semua frame, supaya warna konsisten sepanjang animasi.
        val sw = 200
        val sh = (height * sw / width).coerceAtLeast(1)
        val samples = files.mapNotNull { f ->
            repo.decodeRegion(f, region, sw, sh)?.let { b ->
                IntArray(sw * sh).also { b.getPixels(it, 0, sw, 0, 0, sw, sh); b.recycle() }
            }
        }
        val encoder = GifEncoder(out, width, height, Palette.build(samples))
        encoder.start()
        GifSequence.encode(encoder, files.size, width * height, o.delayCs, o.smooth, o.bounce, o.holdEnd,
            pixelsOf = { i ->
                repo.decodeRegion(files[i], region, width, height)?.let { b ->
                    IntArray(width * height).also { b.getPixels(it, 0, width, 0, 0, width, height); b.recycle() }
                }
            },
            onFrame = { done, total -> progress(Phase.ENCODE, done, total) })
        encoder.finish()
    }
}
