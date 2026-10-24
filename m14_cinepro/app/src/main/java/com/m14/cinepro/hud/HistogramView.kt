package com.m14.cinepro.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.m14.cinepro.core.Models
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * M14 CinePro — 64-bin RGB display histogram (viewfinder scopes, bottom-left of the HUD).
 *
 * Input: the fresh ByteBuffer copy delivered by [com.m14.cinepro.core.EngineListener.onHistogram]
 * — 192 little-endian uint32 counts laid out as R=[0,64) G=[64,128) B=[128,192)
 * (see Models.HISTOGRAM_BINS, computed at 10 fps by histogram_compute.glsl).
 *
 * Render: 64 vertical bars (the gap shrinks adaptively on narrow scopes — QA Phase 6B5 — so
 * the bars can never collapse to zero width); the R, G and B channel columns are overlaid with
 * alpha (screen-style additive look), each normalized against the global max so channel
 * relationships stay readable. Backing is semi-transparent dark cinema panels.
 */
class HistogramView(context: Context) : View(context) {

    private val bins = IntArray(Models.HISTOGRAM_BINS * 3)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB314181F.toInt()
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = 0x33F2F4F7
    }
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB0FF3B30.toInt() }
    private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB032D74B.toInt() }
    private val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB00A84FF.toInt() }

    private val padPx: Float
    private val gapPx: Float = 2f * resources.displayMetrics.density

    init {
        val d = resources.displayMetrics.density
        padPx = 5f * d
        contentDescription = "RGB histogram"
    }

    /** Store a 192-int copy of the incoming histogram buffer and schedule a redraw. */
    fun onHistogram(buffer: ByteBuffer) {
        synchronized(bins) {
            val dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            val n = min(bins.size, dup.remaining() / 4)
            while (i < n) {
                bins[i] = dup.int.coerceAtLeast(0)
                i++
            }
            while (i < bins.size) {
                bins[i] = 0
                i++
            }
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)

        val innerW = width - padPx * 2f
        val innerH = height - padPx * 2f
        if (innerW <= 0f || innerH <= 0f) return

        val barCount = Models.HISTOGRAM_BINS
        // QA Phase 6: narrow views must never produce barW <= 0 — shrink the gap, then the bins.
        var gap = gapPx
        if (gap >= innerW / barCount) gap = 0.5f * resources.displayMetrics.density
        var barW = (innerW - gap * (barCount - 1)) / barCount
        if (barW <= 0f) {
            gap = 0f
            barW = innerW / barCount
        }

        val maxCount: Int = synchronized(bins) {
            var m = 1
            for (v in bins) if (v > m) m = v
            m
        }.coerceAtLeast(1)

        val bottom = height - padPx
        val maxF = maxCount.toFloat()

        synchronized(bins) {
            for (i in 0 until barCount) {
                val x = padPx + i * (barW + gap)
                val r = bins[i] / maxF
                val g = bins[barCount + i] / maxF
                val b = bins[barCount * 2 + i] / maxF
                val hr = r * innerH
                val hg = g * innerH
                val hb = b * innerH
                // Overlaid channels: blue first so red/green sit on top (classic RGB parade mix).
                if (hb > 0f) canvas.drawRect(x, bottom - hb, x + barW, bottom, bluePaint)
                if (hg > 0f) canvas.drawRect(x, bottom - hg, x + barW, bottom, greenPaint)
                if (hr > 0f) canvas.drawRect(x, bottom - hr, x + barW, bottom, redPaint)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = resources.displayMetrics.density
        val w = View.resolveSize((200f * d).toInt(), widthMeasureSpec)
        val h = View.resolveSize((110f * d).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }
}
