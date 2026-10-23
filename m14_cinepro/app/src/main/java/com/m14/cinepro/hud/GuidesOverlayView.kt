package com.m14.cinepro.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.m14.cinepro.core.Models
import com.m14.cinepro.core.SettingsStore
import java.util.Locale

/**
 * M14 CinePro — full-size framing overlay drawn UNDER the HUD bars, ABOVE the viewfinder.
 *
 * Renders (all driven straight from [SettingsStore] at draw time):
 *  - Aspect mask bars for [Models.GuidePreset] (cinema letterboxing, 2.39 / 2.00 / 1.85 / 4:3 / 9:16).
 *  - Grid overlays per [Models.GridOverlay]: THIRDS / CROSSHAIR / HORIZON.
 *  - De-squeeze readout per [Models.DesqueezePreset]: with the GL preview horizontally
 *    stretched by the anamorphic factor (stage4_shaders.md §9, matrix.setScale(f, 1.0f)),
 *    the full frame already represents the projected aspect — the overlay annotates it with
 *    the effective delivery ratio and, when no aspect guide is active, draws subtle corner
 *    ticks so the operator can see de-squeeze is armed.
 */
class GuidesOverlayView(context: Context, private val settings: SettingsStore) : View(context) {

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 235
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 85
        strokeWidth = resources.displayMetrics.density
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3DDC84.toInt()
        alpha = 190
        strokeWidth = resources.displayMetrics.density * 1.6f
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        alpha = 70
        strokeWidth = resources.displayMetrics.density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 190
        textSize = 11f * resources.displayMetrics.density
        isFakeBoldText = true
    }

    private val tmpRect = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = "Framing guides"
    }

    /** Re-read settings and redraw (called by HudController.refreshAll). */
    fun refresh() {
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val guide = settings.guide
        var frame: RectF? = null

        if (guide != Models.GuidePreset.OFF) {
            frame = fitRatio(w, h, guide.ratio)
            drawMask(canvas, w, h, frame)
            canvas.drawRect(frame, framePaint)
            canvas.drawText(guide.label, frame.centerX() - textPaint.measureText(guide.label) / 2f,
                frame.bottom - 8f * resources.displayMetrics.density, textPaint)
        }

        when (settings.grid) {
            Models.GridOverlay.THIRDS -> drawThirds(canvas, frame ?: RectF(0f, 0f, w, h))
            Models.GridOverlay.CROSSHAIR -> drawCrosshair(canvas, w, h)
            Models.GridOverlay.HORIZON -> drawHorizon(canvas, w, h)
            Models.GridOverlay.NONE -> Unit
        }

        val dz = settings.desqueeze
        if (dz != Models.DesqueezePreset.X100) {
            drawDesqueeze(canvas, w, h, dz, guide)
        }
    }

    private fun fitRatio(w: Float, h: Float, ratio: Float): RectF {
        val viewRatio = w / h
        return if (ratio >= viewRatio) {
            val fh = w / ratio
            RectF(0f, (h - fh) / 2f, w, (h + fh) / 2f)
        } else {
            val fw = h * ratio
            RectF((w - fw) / 2f, 0f, (w + fw) / 2f, h)
        }
    }

    private fun drawMask(canvas: Canvas, w: Float, h: Float, frame: RectF) {
        // Top band.
        if (frame.top > 0f) canvas.drawRect(0f, 0f, w, frame.top, maskPaint)
        // Bottom band.
        if (frame.bottom < h) canvas.drawRect(0f, frame.bottom, w, h, maskPaint)
        // Left pillar.
        if (frame.left > 0f) canvas.drawRect(0f, frame.top, frame.left, frame.bottom, maskPaint)
        // Right pillar.
        if (frame.right < w) canvas.drawRect(frame.right, frame.top, w, frame.bottom, maskPaint)
    }

    private fun drawThirds(canvas: Canvas, area: RectF) {
        val w3 = area.width() / 3f
        val h3 = area.height() / 3f
        for (i in 1..2) {
            val x = area.left + w3 * i
            val y = area.top + h3 * i
            canvas.drawLine(x, area.top, x, area.bottom, linePaint)
            canvas.drawLine(area.left, y, area.right, y, linePaint)
        }
    }

    private fun drawCrosshair(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val len = 22f * resources.displayMetrics.density
        val gap = 6f * resources.displayMetrics.density
        canvas.drawLine(cx - len - gap, cy, cx - gap, cy, linePaint)
        canvas.drawLine(cx + gap, cy, cx + len + gap, cy, linePaint)
        canvas.drawLine(cx, cy - len - gap, cx, cy - gap, linePaint)
        canvas.drawLine(cx, cy + gap, cx, cy + len + gap, linePaint)
        canvas.drawPoint(cx, cy, accentPaint)
    }

    private fun drawHorizon(canvas: Canvas, w: Float, h: Float) {
        val cy = h / 2f
        canvas.drawLine(0f, cy, w, cy, linePaint)
        val tick = 10f * resources.displayMetrics.density
        canvas.drawLine(0f, cy - tick, 0f, cy + tick, accentPaint)
        canvas.drawLine(w, cy - tick, w, cy + tick, accentPaint)
    }

    private fun drawDesqueeze(
        canvas: Canvas,
        w: Float,
        h: Float,
        dz: Models.DesqueezePreset,
        guide: Models.GuidePreset
    ) {
        val d = resources.displayMetrics.density
        val finalRatio = (w / h) * dz.factor
        val label = String.format(Locale.US, "%s -> %.2f:1", dz.label, finalRatio)
        val tw = textPaint.measureText(label)
        val baseline = h - 12f * d

        if (guide == Models.GuidePreset.OFF) {
            // Corner ticks so the operator sees the de-squeeze frame is armed.
            val tick = 16f * d
            val inset = 8f * d
            val corners = floatArrayOf(
                inset, inset, inset + tick, inset,                                    // TL h
                inset, inset, inset, inset + tick,                                    // TL v
                w - inset - tick, inset, w - inset, inset,                            // TR h
                w - inset, inset, w - inset, inset + tick,                            // TR v
                inset, h - inset, inset + tick, h - inset,                            // BL h
                inset, h - inset - tick, inset, h - inset,                            // BL v
                w - inset - tick, h - inset, w - inset, h - inset,                    // BR h
                w - inset, h - inset - tick, w - inset, h - inset                     // BR v
            )
            var i = 0
            while (i < corners.size) {
                canvas.drawLine(corners[i], corners[i + 1], corners[i + 2], corners[i + 3], accentPaint)
                i += 4
            }
        }

        tmpRect.set(w / 2f - tw / 2f - 6f * d, baseline - textPaint.textSize - 4f * d,
            w / 2f + tw / 2f + 6f * d, baseline + 4f * d)
        canvas.drawText(label, tmpRect.centerX() - tw / 2f, baseline, textPaint)
    }
}
