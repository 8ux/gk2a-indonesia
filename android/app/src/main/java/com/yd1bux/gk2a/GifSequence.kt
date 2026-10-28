package com.yd1bux.gk2a

/**
 * Urutan frame GIF (tanpa ketergantungan Android): crossfade, bolak-balik, tahan di frame terbaru.
 * [pixelsOf] memberi piksel ARGB frame sumber ke-i (null = lewati frame itu).
 */
object GifSequence {
    const val BLENDS = 2
    const val HOLD_CS = 150

    fun outputFrames(count: Int, smooth: Boolean, bounce: Boolean): Int {
        val order = if (bounce && count > 2) 2 * count - 2 else count
        val transitions = when {
            !smooth || count < 2 -> 0
            bounce && count > 2 -> order
            else -> order - 1
        }
        return order + transitions * BLENDS
    }

    fun encode(
        encoder: GifEncoder,
        count: Int,
        size: Int,
        delayCs: Int,
        smooth: Boolean,
        bounce: Boolean,
        holdEnd: Boolean,
        pixelsOf: (Int) -> IntArray?,
        onFrame: (written: Int, total: Int) -> Unit,
    ) {
        val order = (0 until count).toMutableList()
        if (bounce && count > 2) order += (count - 2 downTo 1)
        val total = outputFrames(count, smooth, bounce)
        val blend = IntArray(size)
        var written = 0
        var first: IntArray? = null
        var prev: IntArray? = null

        fun emit(px: IntArray, delay: Int) {
            encoder.addFrame(px, delay)
            onFrame(++written, total)
        }

        fun crossfade(a: IntArray, b: IntArray) {
            for (k in 1..BLENDS) {
                mix(a, b, k.toFloat() / (BLENDS + 1), blend)
                emit(blend, delayCs)
            }
        }

        order.forEachIndexed { step, i ->
            val cur = pixelsOf(i) ?: return@forEachIndexed
            val p = prev
            if (smooth && p != null) crossfade(p, cur)
            val isLatest = i == count - 1 && step == count - 1
            emit(cur, if (holdEnd && isLatest) HOLD_CS else delayCs)
            if (first == null) first = cur
            prev = cur
        }
        // Bolak-balik + halus: sambungkan kembali ke frame pertama agar loop mulus.
        val p = prev
        val f = first
        if (smooth && bounce && count > 2 && p != null && f != null) crossfade(p, f)
    }

    private fun mix(a: IntArray, b: IntArray, t: Float, out: IntArray) {
        val wb = (t * 256).toInt()
        val wa = 256 - wb
        for (i in out.indices) {
            val x = a[i]
            val y = b[i]
            val r = (((x shr 16) and 0xFF) * wa + ((y shr 16) and 0xFF) * wb) shr 8
            val g = (((x shr 8) and 0xFF) * wa + ((y shr 8) and 0xFF) * wb) shr 8
            val bl = ((x and 0xFF) * wa + (y and 0xFF) * wb) shr 8
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }
}
