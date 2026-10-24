package com.m14.cinepro.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.m14.cinepro.core.Models
import com.m14.cinepro.core.SettingsStore
import kotlin.math.abs

/**
 * M14 CinePro — full-size focus/touch overlay drawn UNDER the HUD bars, ABOVE the guides.
 *
 * Responsibilities:
 *  - Owns ALL viewfinder-area gestures: single tap -> focus tap (normalized coords),
 *    double tap + fast vertical swipe (> 80 dp) -> HUD chrome toggle, two-finger pinch ->
 *    multiplicative zoom delta, forwarded via the [onTap] / [onDoubleTap] / [onVerticalSwipe] /
 *    [onZoom] callbacks wired by [HudController].
 *  - Draws the animated white corner-bracket tap box.
 *  - Draws persistent F1/F2/F3 focus markers from [SettingsStore.focusMarkers].
 */
class FocusOverlayView(context: Context, private val settings: SettingsStore) : View(context) {

    companion object {
        private const val TAP_BOX_ANIM_MS = 700L
    }

    /** Single tap on the picture area, normalized 0..1 x / y. */
    var onTap: ((Float, Float) -> Unit)? = null

    /** Double tap anywhere on the picture area — HUD chrome toggle. */
    var onDoubleTap: (() -> Unit)? = null

    /** Fast vertical swipe > 80 dp — HUD chrome toggle. */
    var onVerticalSwipe: (() -> Unit)? = null

    /** Multiplicative zoom delta from a 2-finger pinch (QA Phase 6). */
    var onZoom: ((Float) -> Unit)? = null

    private var tapNx = -1f
    private var tapNy = -1f
    private var tapStartMs = 0L

    private val density = resources.displayMetrics.density

    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF3DDC84.toInt()
        strokeWidth = 2.2f * density
    }
    private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x663DDC84.toInt()
    }
    private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        isFakeBoldText = true
        setShadowLayer(3f, 0f, 0f, 0xB3000000.toInt())
    }

    /** True while a two-finger scale is live — suppresses the stray tap a pinch can plant. */
    private var scaleFocus = false

    private val scaleDetector =
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val f = detector.scaleFactor
                if (!f.isNaN() && !f.isInfinite() && f > 0f) onZoom?.invoke(f)
                return true
            }
        })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (scaleFocus) return true
            // CP1 law: taps map through the 16:9 picture rect (the GL letterbox), not the full
            // window — pillar/letterbox taps clamp to the picture edge. De-squeeze (QA Phase 2)
            // stretches the rect horizontally, so taps map through the stretched picture too.
            val pic = Models.pictureRect(width, height, settings.desqueeze.factor)
            val nx = ((e.x - pic[0]) / pic[2].coerceAtLeast(1f)).coerceIn(0f, 1f)
            val ny = ((e.y - pic[1]) / pic[3].coerceAtLeast(1f)).coerceIn(0f, 1f)
            showTapBox(nx, ny)
            onTap?.invoke(nx, ny)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap?.invoke()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val dy = e2.y - (e1?.y ?: e2.y)
            val dx = e2.x - (e1?.x ?: e2.x)
            if (abs(dy) > 80f * density && abs(dy) > abs(dx) && abs(velocityY) > 400f * density) {
                onVerticalSwipe?.invoke()
                return true
            }
            return false
        }
    })

    init {
        isClickable = true
        isFocusable = false
        contentDescription = "Viewfinder touch area"
        // Scale first (QA Phase 6 pinch-zoom), then the single-finger gesture set — the gesture
        // behavior is unchanged; an active pinch only suppresses the tap-focus it would plant.
        setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            scaleFocus = scaleDetector.isInProgress()
            gestures.onTouchEvent(event)
        }
    }

    /** Show the animated focus box at the given normalized position. */
    fun showTapBox(nx: Float, ny: Float) {
        tapNx = nx.coerceIn(0f, 1f)
        tapNy = ny.coerceIn(0f, 1f)
        tapStartMs = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    /** Re-read focus markers from settings and redraw. */
    fun refreshMarkers() {
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // CP1 law: the live picture is the centered 16:9 rect — reticle + markers map into it.
        // De-squeeze (QA Phase 2) stretches the rect horizontally; overlays follow the stretch.
        val pic = Models.pictureRect(width, height, settings.desqueeze.factor)
        val picLeft = pic[0]
        val picTop = pic[1]
        val picW = pic[2]
        val picH = pic[3]

        drawMarkers(canvas, picLeft, picTop, picW, picH)

        if (tapStartMs > 0L) {
            val elapsed = SystemClock.uptimeMillis() - tapStartMs
            if (elapsed >= TAP_BOX_ANIM_MS) {
                tapStartMs = 0L
            } else {
                drawTapBox(canvas, picLeft, picTop, picW, picH, elapsed / TAP_BOX_ANIM_MS.toFloat())
                postInvalidateOnAnimation()
            }
        }
    }

    private fun drawTapBox(canvas: Canvas, picLeft: Float, picTop: Float, picW: Float, picH: Float, progress: Float) {
        // Box settles from a large bracket ring down to the target size while fading out.
        val targetHalf = 34f * density
        val half = targetHalf + (1f - progress) * 22f * density
        val cx = picLeft + tapNx * picW
        val cy = picTop + tapNy * picH
        val len = half * 0.42f
        val prevAlpha = bracketPaint.alpha
        bracketPaint.alpha = ((1f - progress) * 255f).toInt().coerceIn(0, 255)

        val x0 = cx - half
        val x1 = cx + half
        val y0 = cy - half
        val y1 = cy + half

        // Four corner brackets.
        canvas.drawLine(x0, y0, x0 + len, y0, bracketPaint)
        canvas.drawLine(x0, y0, x0, y0 + len, bracketPaint)
        canvas.drawLine(x1 - len, y0, x1, y0, bracketPaint)
        canvas.drawLine(x1, y0, x1, y0 + len, bracketPaint)
        canvas.drawLine(x0, y1, x0 + len, y1, bracketPaint)
        canvas.drawLine(x0, y1 - len, x0, y1, bracketPaint)
        canvas.drawLine(x1 - len, y1, x1, y1, bracketPaint)
        canvas.drawLine(x1, y1 - len, x1, y1, bracketPaint)

        // Center dot.
        canvas.drawPoint(cx, cy, bracketPaint)
        bracketPaint.alpha = prevAlpha
    }

    private fun drawMarkers(canvas: Canvas, picLeft: Float, picTop: Float, picW: Float, picH: Float) {
        for (i in 0..2) {
            val m = settings.focusMarkers.getOrNull(i) ?: continue
            val cx = picLeft + m[0] * picW
            val cy = picTop + m[1] * picH
            val r = 9f * density
            canvas.drawCircle(cx, cy, r, markerFillPaint)
            canvas.drawCircle(cx, cy, r, markerPaint)
            val label = "F${i + 1}"
            canvas.drawText(label, cx + r + 3f * density, cy + markerTextPaint.textSize * 0.35f, markerTextPaint)
        }
    }
}
