package com.yd1bux.gk2a

import java.io.OutputStream

/**
 * Encoder GIF animasi sederhana (tanpa library): satu palet global 256 warna untuk semua frame,
 * kompresi LZW standar, animasi berulang terus.
 *
 * Piksel masuk sebagai ARGB (IntArray, seperti Bitmap.getPixels).
 */
class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    private val palette: IntArray,
) {
    private val lut = Palette.lookupTable(palette)

    fun start() {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(width)
        writeShort(height)
        out.write(0xF7) // ada palet global, resolusi warna 8 bit, ukuran palet 256
        out.write(0)    // indeks warna latar
        out.write(0)    // rasio aspek piksel
        for (i in 0 until 256) {
            val c = palette.getOrElse(i) { 0 }
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }
        // NETSCAPE2.0: ulangi animasi tanpa henti
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))
    }

    /** [pixels] berukuran width*height; [delayCs] jeda dalam seperseratus detik. */
    fun addFrame(pixels: IntArray, delayCs: Int) {
        require(pixels.size == width * height) { "Ukuran frame tidak sesuai" }
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00))
        writeShort(delayCs)
        out.write(byteArrayOf(0x00, 0x00))
        out.write(0x2C)
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        out.write(0x00) // tanpa palet lokal, tidak interlace
        val indices = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            indices[i] = lut[((c shr 9) and 0x7C00) or ((c shr 6) and 0x3E0) or ((c shr 3) and 0x1F)]
        }
        LzwWriter(out).write(indices)
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    private fun writeShort(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}

/** Pembentukan palet dari histogram warna beberapa frame contoh. */
object Palette {
    /** Warna yang selalu ada di palet: hitam, putih, garis pantai (kuning), batas negara (oranye). */
    private val RESERVED = intArrayOf(0x000000, 0xFFFFFF, 0xFFFF00, 0xFF8C00)

    fun build(samples: List<IntArray>): IntArray {
        val count = IntArray(4096)
        val sumR = LongArray(4096)
        val sumG = LongArray(4096)
        val sumB = LongArray(4096)
        for (px in samples) for (c in px) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val bin = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            count[bin]++
            sumR[bin] += r.toLong()
            sumG[bin] += g.toLong()
            sumB[bin] += b.toLong()
        }
        val top = (0 until 4096).filter { count[it] > 0 }
            .sortedByDescending { count[it] }
            .take(256 - RESERVED.size)
        val palette = IntArray(256)
        RESERVED.copyInto(palette)
        top.forEachIndexed { i, bin ->
            val n = count[bin]
            palette[RESERVED.size + i] =
                ((sumR[bin] / n).toInt() shl 16) or ((sumG[bin] / n).toInt() shl 8) or (sumB[bin] / n).toInt()
        }
        return palette
    }

    /** Tabel 32768 entri (RGB 5-5-5) ke indeks palet terdekat. */
    fun lookupTable(palette: IntArray): ByteArray {
        val lut = ByteArray(32768)
        for (key in 0 until 32768) {
            val r = ((key shr 10) and 0x1F) * 255 / 31
            val g = ((key shr 5) and 0x1F) * 255 / 31
            val b = (key and 0x1F) * 255 / 31
            var best = 0
            var bestDist = Int.MAX_VALUE
            for (i in palette.indices) {
                val c = palette[i]
                val dr = r - ((c shr 16) and 0xFF)
                val dg = g - ((c shr 8) and 0xFF)
                val db = b - (c and 0xFF)
                val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
            lut[key] = best.toByte()
        }
        return lut
    }
}

/** LZW GIF dengan ukuran kode 9..12 bit (mengikuti alur encoder klasik GIFCOMPR). */
private class LzwWriter(private val out: OutputStream) {
    private val block = ByteArray(255)
    private var blockLen = 0
    private var bitBuf = 0
    private var bitCount = 0

    // Tabel hash kunci (prefix shl 8 | byte) -> kode, tanpa boxing.
    private val keys = IntArray(HASH_SIZE)
    private val codes = IntArray(HASH_SIZE)

    fun write(indices: ByteArray) {
        out.write(MIN_CODE_SIZE)
        var codeSize = MIN_CODE_SIZE + 1
        var nextCode = EOI + 1
        keys.fill(-1)
        emit(CLEAR, codeSize)
        if (indices.isEmpty()) {
            emit(EOI, codeSize)
            flush()
            return
        }
        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val c = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            var h = (key * 0x9E3779B1.toInt()) ushr (32 - HASH_BITS)
            while (keys[h] != -1 && keys[h] != key) h = (h + 1) and (HASH_SIZE - 1)
            if (keys[h] == key) {
                prefix = codes[h]
                continue
            }
            emit(prefix, codeSize)
            if (nextCode > (1 shl codeSize) - 1 && codeSize < MAX_BITS) codeSize++
            if (nextCode < MAX_CODE) {
                keys[h] = key
                codes[h] = nextCode++
            } else {
                emit(CLEAR, codeSize)
                keys.fill(-1)
                nextCode = EOI + 1
                codeSize = MIN_CODE_SIZE + 1
            }
            prefix = c
        }
        emit(prefix, codeSize)
        if (nextCode > (1 shl codeSize) - 1 && codeSize < MAX_BITS) codeSize++
        emit(EOI, codeSize)
        flush()
    }

    private fun emit(code: Int, size: Int) {
        bitBuf = bitBuf or (code shl bitCount)
        bitCount += size
        while (bitCount >= 8) {
            addByte(bitBuf and 0xFF)
            bitBuf = bitBuf ushr 8
            bitCount -= 8
        }
    }

    private fun addByte(b: Int) {
        block[blockLen++] = b.toByte()
        if (blockLen == 255) writeBlock()
    }

    private fun writeBlock() {
        if (blockLen == 0) return
        out.write(blockLen)
        out.write(block, 0, blockLen)
        blockLen = 0
    }

    private fun flush() {
        if (bitCount > 0) addByte(bitBuf and 0xFF)
        bitBuf = 0
        bitCount = 0
        writeBlock()
        out.write(0) // akhir data gambar
    }

    companion object {
        private const val MIN_CODE_SIZE = 8
        private const val CLEAR = 256
        private const val EOI = 257
        private const val MAX_BITS = 12
        private const val MAX_CODE = 4096
        private const val HASH_BITS = 13
        private const val HASH_SIZE = 1 shl HASH_BITS
    }
}
