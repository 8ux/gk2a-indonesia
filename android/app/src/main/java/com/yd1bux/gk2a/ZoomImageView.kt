package com.yd1bux.gk2a

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/**
 * ImageView dengan pinch-zoom, geser, dan ketuk-dua-kali.
 *
 * Zoom disimpan di koordinat layar (userMatrix), terpisah dari skala "fit" tiap bitmap,
 * jadi posisi zoom tetap sama saat frame animasi berganti atau diganti versi resolusi penuh.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {

    private val baseMatrix = Matrix()
    private val userMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val values = FloatArray(9)
    private var lastAspect = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                userMatrix.postTranslate(-dx, -dy)
                applyMatrix()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (userScale() > 1.05f) userMatrix.reset() else zoomBy(MAX_SCALE / 2.5f, e.x, e.y)
                applyMatrix()
                return true
            }
        })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        val aspect = if (bm == null || bm.height == 0) 0f else bm.width.toFloat() / bm.height
        if (aspect != lastAspect) {
            userMatrix.reset()
            lastAspect = aspect
        }
        updateBase()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        userMatrix.reset()
        updateBase()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(userScale() > 1.01f)
        return true
    }

    /** Bagian gambar yang sedang terlihat (0..1 terhadap lebar/tinggi gambar), atau null bila tidak di-zoom. */
    fun visibleFraction(): RectF? {
        val d = drawable ?: return null
        if (userScale() < 1.05f) return null
        val inverse = Matrix()
        if (!drawMatrix.invert(inverse)) return null
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        inverse.mapRect(r)
        val w = d.intrinsicWidth.toFloat()
        val h = d.intrinsicHeight.toFloat()
        return RectF(
            (r.left / w).coerceIn(0f, 1f), (r.top / h).coerceIn(0f, 1f),
            (r.right / w).coerceIn(0f, 1f), (r.bottom / h).coerceIn(0f, 1f),
        )
    }

    private fun userScale(): Float {
        userMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val current = userScale()
        val target = (current * factor).coerceIn(1f, MAX_SCALE)
        val f = target / current
        userMatrix.postScale(f, f, fx, fy)
        applyMatrix()
    }

    /** Skala dasar: bitmap pas di tengah view (seperti fitCenter). */
    private fun updateBase() {
        val d = drawable ?: return
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()
        if (iw <= 0 || ih <= 0 || width == 0 || height == 0) return
        val s = minOf(width / iw, height / ih)
        baseMatrix.setScale(s, s)
        baseMatrix.postTranslate((width - iw * s) / 2f, (height - ih * s) / 2f)
        applyMatrix()
    }

    /** Gabungkan matriks dan jaga agar gambar tidak tergeser keluar layar. */
    private fun applyMatrix() {
        val d = drawable ?: return
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(userMatrix)
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(r)
        val dx = when {
            r.width() <= width -> (width - r.width()) / 2f - r.left
            r.left > 0 -> -r.left
            r.right < width -> width - r.right
            else -> 0f
        }
        val dy = when {
            r.height() <= height -> (height - r.height()) / 2f - r.top
            r.top > 0 -> -r.top
            r.bottom < height -> height - r.bottom
            else -> 0f
        }
        if (dx != 0f || dy != 0f) {
            userMatrix.postTranslate(dx, dy)
            drawMatrix.postTranslate(dx, dy)
        }
        imageMatrix = drawMatrix
    }

    companion object {
        private const val MAX_SCALE = 8f
    }
}
