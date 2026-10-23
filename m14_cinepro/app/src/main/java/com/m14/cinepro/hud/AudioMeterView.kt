package com.m14.cinepro.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * M14 CinePro — stereo audio meter (viewfinder scopes, bottom-right of the HUD).
 *
 * Two horizontal bars (L top, R bottom) on a -60..0 dBFS scale:
 *  - RMS fill with a green -> yellow -> red level gradient.
 *  - White peak-hold tick at the peak position (decay is already applied upstream by the
 *    AudioEngine, this view is deliberately dumb).
 *  - Thin white reference marks with tiny labels at -18 and -20 dBFS (VU/PPM reference,
 *    see Models.AudioMeteringMode / stage5_media.md §5.6).
 */
class AudioMeterView(context: Context) : View(context) {

    private var peakL = -60f
    private var rmsL = -60f
    private var peakR = -60f
    private var rmsR = -60f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB314181F.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = 0x33F2F4F7
    }
    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = resources.displayMetrics.density * 0.8f
        color = 0xB3FFFFFF.toInt()
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = resources.displayMetrics.density * 2f
        color = 0xFFFFFFFF.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6F2F4F7.toInt()
        textSize = 8f * resources.displayMetrics.density
        isFakeBoldText = true
    }
    private val dimLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80F2F4F7.toInt()
        textSize = 8f * resources.displayMetrics.density
    }

    private val rmsPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gradientShader: LinearGradient? = null
    private var gradientWidth = 0f

    init {
        contentDescription = "Audio meters"
    }

    /** dBFS levels, expected in -60..0. Values outside are clamped. */
    fun setLevels(peakLeftDb: Float, rmsLeftDb: Float, peakRightDb: Float, rmsRightDb: Float) {
        peakL = peakLeftDb.coerceIn(-60f, 0f)
        rmsL = rmsLeftDb.coerceIn(-60f, 0f)
        peakR = peakRightDb.coerceIn(-60f, 0f)
        rmsR = rmsRightDb.coerceIn(-60f, 0f)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)

        val d = resources.displayMetrics.density
        val pad = 6f * d
        val gutter = 12f * d // left gutter for the L / R channel letters
        val barTopPad = 4f * d

        val barX0 = pad + gutter
        val barX1 = width - pad
        val barW = barX1 - barX0
        if (barW <= 0f) return

        val barH = (height - pad * 2f - barTopPad * 2f) / 2f
        if (barH <= 0f) return

        if (gradientShader == null || gradientWidth != barW) {
            gradientShader = LinearGradient(
                barX0, 0f, barX1, 0f,
                intArrayOf(0xFF2ECC71.toInt(), 0xFFF1C40F.toInt(), 0xFFE74C3C.toInt()),
                floatArrayOf(0f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            rmsPaint.shader = gradientShader
            gradientWidth = barW
        }

        drawChannel(canvas, barX0, barX1, pad, barH, "L", rmsL, peakL, showRefLabels = true)
        drawChannel(
            canvas, barX0, barX1, pad + barH + barTopPad * 2f, barH,
            "R", rmsR, peakR, showRefLabels = false
        )

        // Scale endpoints (-60 .. 0 dBFS).
        canvas.drawText("-60", barX0, height - pad * 0.2f, dimLabelPaint)
        val zeroW = labelPaint.measureText("0")
        canvas.drawText("0", barX1 - zeroW, height - pad * 0.2f, labelPaint)
    }

    private fun drawChannel(
        canvas: Canvas,
        x0: Float,
        x1: Float,
        top: Float,
        barH: Float,
        letter: String,
        rmsDb: Float,
        peakDb: Float,
        showRefLabels: Boolean
    ) {
        val bottom = top + barH

        // Channel letter.
        canvas.drawText(letter, x0 - resources.displayMetrics.density * 9f, top + barH * 0.78f, labelPaint)

        // RMS fill.
        val rmsX = dbToX(x0, x1, rmsDb)
        canvas.drawRect(x0, top, rmsX, bottom, rmsPaint)

        // Peak-hold tick.
        val peakX = dbToX(x0, x1, peakDb)
        canvas.drawLine(peakX, top, peakX, bottom, peakPaint)

        // Reference marks at -18 and -20 dBFS.
        for (ref in intArrayOf(-18, -20)) {
            val rx = dbToX(x0, x1, ref.toFloat())
            canvas.drawLine(rx, top, rx, bottom, refPaint)
            if (showRefLabels) {
                val label = "$ref"
                val w = labelPaint.measureText(label)
                val lx = (rx - w / 2f).coerceIn(x0, x1 - w)
                canvas.drawText(label, lx, top - 1f, labelPaint)
            }
        }

        // 0 dBFS end marker.
        canvas.drawLine(x1, top, x1, bottom, refPaint)
    }

    private fun dbToX(x0: Float, x1: Float, db: Float): Float {
        val t = ((db + 60f) / 60f).coerceIn(0f, 1f)
        return x0 + t * (x1 - x0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = resources.displayMetrics.density
        val w = resolve((160f * d).toInt(), widthMeasureSpec)
        val h = resolve((110f * d).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    private fun resolve(wanted: Int, spec: Int): Int {
        val mode = View.MeasureSpec.getMode(spec)
        val size = View.MeasureSpec.getSize(spec)
        return when (mode) {
            View.MeasureSpec.EXACTLY -> size
            View.MeasureSpec.AT_MOST -> minOf(wanted, size)
            else -> wanted
        }
    }
}
