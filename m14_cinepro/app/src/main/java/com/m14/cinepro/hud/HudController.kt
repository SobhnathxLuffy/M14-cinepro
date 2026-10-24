package com.m14.cinepro.hud

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.m14.cinepro.R
import com.m14.cinepro.core.Models
import com.m14.cinepro.core.SettingsStore
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * M14 CinePro — the 3-zone Blackmagic landscape HUD (CP8 law, V2 rewrite).
 *
 * Layered into the activity's [R.id.hud_root] FrameLayout (the full-screen GL viewfinder sits at
 * index 0, added by MainActivity):
 *
 *   [1] GuidesOverlayView — aspect masks / grids / de-squeeze (mapped to the 16:9 picture rect)
 *   [2] FocusOverlayView  — tap reticle + F1/F2/F3 markers, owns picture-area gestures
 *   [3] topZone           — TOP bar (48 dp): scrollable pills (FPS..TINT) | TIMECODE | scrollable
 *                           pills (ZOOM HEVC TEMP/BATT RESET ALL) + floating slider row
 *   [4] rightColumn       — RIGHT (72 dp): REC circle, A/AF LOCK, F1/F2/F3, MEDIA, SLATE
 *   [5] bottomZone        — BOTTOM bar (48 dp): 64-bin histogram | PEAK ZEBRA FALSE COLOR LUT
 *                           GUIDES EIS | stereo L/R VU meter, slider row floats above the bar
 *
 * Overlap/glitch law: there is NO PopupWindow anywhere. Tapping a parameter opens exactly ONE
 * in-layout slider row (under the top bar for top parameters, above the bottom bar for bottom
 * pills); every opener first closes the open panel — including one attached to the other zone —
 * so UI elements can never stack. TAG "M14Hud".
 */
class HudController(
    private val activity: Activity,
    val settings: SettingsStore,
    private val hooks: Hooks
) {

    /** Decisions delegated back to MainActivity. */
    interface Hooks {
        fun onRecordToggle()
        fun onSlateOpen()
        fun onEisToggled(on: Boolean)
        fun onTorchToggled(on: Boolean)
        fun onFormatChanged(preset: Models.ResPreset)      // MainActivity rebuilds the session
        fun onBitrateChanged(p: Models.BitratePreset)
        fun onFocusTap(nx: Float, ny: Float)               // normalized picture coords
        fun onMarkerTap(index: Int)                        // 0..2 short tap -> AF at marker
        fun onMarkerLongPress(index: Int)                  // save last tap point as marker
        fun onMediaOpen()                                  // MEDIA button -> MainActivity opens the Media Pool
        fun onResetAll()                                   // RESET ALL -> MainActivity wipes settings + rebuilds session
    }

    // -----------------------------------------------------------------------------------------
    // Optional engine wiring (installed by MainActivity; HUD never touches engine classes)
    // -----------------------------------------------------------------------------------------

    /** MainActivity opens the framework document picker for .cube import. */
    var lutImportRequester: (() -> Unit)? = null

    /** MainActivity forwards to GlViewfinder.applyLut(preset, path). */
    var lutApplier: ((Models.LutPreset, String?) -> Unit)? = null

    /** MainActivity forwards to CameraEngine.setZoomRatio (HUD never touches engine classes). */
    var zoomApplier: ((Float) -> Unit)? = null

    /** MainActivity pushes changed exposure/WB settings into the camera engine. */
    var onSettingsDirty: (() -> Unit)? = null

    // -----------------------------------------------------------------------------------------
    // State + views
    // -----------------------------------------------------------------------------------------

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = activity.resources.displayMetrics.density

    private val root: FrameLayout =
        (activity.findViewById(R.id.hud_root) as? FrameLayout) ?: createFallbackRoot()

    private lateinit var guidesOverlay: GuidesOverlayView
    private lateinit var focusOverlay: FocusOverlayView
    private lateinit var topZone: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var topSliderRow: LinearLayout
    private lateinit var bottomZone: LinearLayout
    private lateinit var bottomSliderRow: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var rightColumn: LinearLayout
    private lateinit var histogramView: HistogramView
    private lateinit var audioMeter: AudioMeterView
    private lateinit var recButton: RecButton
    private lateinit var lockButton: TextView
    private lateinit var statusChip: TextView

    private lateinit var fpsPill: TextView
    private lateinit var shutterPill: TextView
    private lateinit var irisPill: TextView
    private lateinit var isoPill: TextView
    private lateinit var wbPill: TextView
    private lateinit var tintPill: TextView
    private lateinit var tcPill: TextView
    private lateinit var codecPill: TextView
    private lateinit var sysPill: TextView
    private lateinit var zoomPill: TextView
    private lateinit var resetAllPill: TextView
    private lateinit var peakPill: TextView
    private lateinit var zebraPill: TextView
    private lateinit var fcPill: TextView
    private lateinit var lutPill: TextView
    private lateinit var guidesPill: TextView
    private lateinit var eisPill: TextView
    private val markerButtons = arrayOfNulls<TextView>(3)

    /** The one open panel id, or null. Opening a panel closes every other panel first. */
    private var openPanelId: String? = null

    /** Live rack-zoom servo (QA Phase 6); cancelled when a new target arrives or on destroy. */
    private var zoomRackAnim: ValueAnimator? = null
    private var chromeVisible = true
    private var lastTap: FloatArray? = null

    private var statusText = ""
    private var systemStatus = ""
    private var telemetryHint = ""

    // -----------------------------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------------------------

    init {
        buildOverlays()
        buildTopZone()
        buildRightColumn()
        buildBottomZone()
        buildStatusChip()
        installRootGestures()
        refreshAll()
    }

    private fun createFallbackRoot(): FrameLayout {
        val f = FrameLayout(activity)
        f.id = R.id.hud_root
        activity.addContentView(
            f,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return f
    }

    private fun buildOverlays() {
        guidesOverlay = GuidesOverlayView(activity, settings)
        root.addView(
            guidesOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        focusOverlay = FocusOverlayView(activity, settings)
        root.addView(
            focusOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        focusOverlay.onTap = { nx, ny ->
            lastTap = floatArrayOf(nx, ny)
            hooks.onFocusTap(nx, ny)
        }
        focusOverlay.onDoubleTap = { toggleChrome() }
        focusOverlay.onVerticalSwipe = { toggleChrome() }
        // QA Phase 6: two-finger pinch = multiplicative zoom delta (direct apply — a pinch must
        // never queue behind the 1.5 s rack servo, so the servo is cancelled here).
        focusOverlay.onZoom = { factor ->
            zoomRackAnim?.cancel()
            val target = (settings.zoomRatio * factor).coerceIn(Models.ZOOM_MIN, Models.ZOOM_MAX)
            settings.zoomRatio = target
            zoomApplier?.invoke(target)
            zoomPill.text = zoomLabel()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Zone 1 — TOP: 48 dp bar (FPS SHUTTER IRIS ISO WB TINT | TIMECODE | HEVC 80M | TEMP/BATT)
    //          + the single floating slider row directly underneath
    // -----------------------------------------------------------------------------------------

    private fun buildTopZone() {
        topZone = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = barBg()
            val pad = dp(4f)
            setPadding(pad, pad, pad, pad)
        }

        fpsPill = makePill(settings.resolution.label) { togglePanel(PANEL_FPS) }
        shutterPill = makePill(settings.shutterLabel()) { togglePanel(PANEL_SHUTTER) }
        irisPill = makePill(irisLabel()) { togglePanel(PANEL_IRIS) }
        isoPill = makePill(settings.isoLabel()) { togglePanel(PANEL_ISO) }
        wbPill = makePill(settings.wbLabel()) { togglePanel(PANEL_WB) }
        tintPill = makePill(tintLabel()) { togglePanel(PANEL_TINT) }
        tcPill = makePill("00:00:00:00", mono = true, sizeSp = 14f, onClick = null)
        zoomPill = makePill(zoomLabel()) { togglePanel(PANEL_ZOOM) }
        codecPill = makePill(codecLabel()) { togglePanel(PANEL_CODEC) }
        sysPill = makePill("—") { togglePanel(PANEL_SYS) }
        resetAllPill = makePill(activity.getString(R.string.btn_reset_all)) { hooks.onResetAll() }

        // QA Phase 6C1/C2 crowding law: the bar is split into two weight-1 scrollable groups with
        // the timecode fixed between them — pills scroll when crowded, nothing is pushed off
        // screen, and the TC stays visually centered while the two groups stay symmetric.
        val leftScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val leftGroup = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        leftGroup.addView(fpsPill, topPillLp())
        leftGroup.addView(shutterPill, topPillLp())
        leftGroup.addView(irisPill, topPillLp())
        leftGroup.addView(isoPill, topPillLp())
        leftGroup.addView(wbPill, topPillLp())
        leftGroup.addView(tintPill, topPillLp())
        leftScroll.addView(leftGroup)
        topBar.addView(
            leftScroll,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        // Centered timecode: fixed wrap-content block between the two weight-1 scroll groups.
        val tcCenter = FrameLayout(activity)
        val tcLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        tcCenter.addView(tcPill, tcLp)
        topBar.addView(
            tcCenter,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Right group: ZOOM | codec | system | RESET ALL. fillViewport + row gravity END keeps the
        // pills hugging the right edge when there is room; they scroll leftward when crowded.
        val rightScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            isFillViewport = true
        }
        val rightGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        rightGroup.addView(zoomPill, topPillLp())
        rightGroup.addView(codecPill, topPillLp())
        rightGroup.addView(sysPill, topPillLp())
        rightGroup.addView(resetAllPill, topPillLp())
        rightScroll.addView(rightGroup)
        topBar.addView(
            rightScroll,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        topZone.addView(
            topBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)
            )
        )

        // The ONE floating slider row docked directly under the bar (CP8 overlap law).
        topSliderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        topZone.addView(
            topSliderRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            topZone,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
    }

    // -----------------------------------------------------------------------------------------
    // Zone 2 — RIGHT: 72 dp transport column (REC, AE/AF LOCK, F1/F2/F3, SLATE)
    // -----------------------------------------------------------------------------------------

    private fun buildRightColumn() {
        rightColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = barBg()
            val pad = dp(4f)
            setPadding(pad, dp(6f), pad, dp(6f))
        }

        recButton = RecButton(activity).apply {
            setOnClickListener { hooks.onRecordToggle() }
        }
        rightColumn.addView(recButton, columnLp(dp(52f), dp(52f), 0))

        lockButton = makeColumnButton(activity.getString(R.string.btn_lock)).apply {
            setOnClickListener {
                settings.aeLock = !settings.aeLock
                onSettingsDirty?.invoke()
                refreshAll()
            }
        }
        rightColumn.addView(lockButton, columnLp(dp(60f), ViewGroup.LayoutParams.WRAP_CONTENT, dp(8f)))

        for (i in 0..2) {
            val btn = makeColumnButton(activity.getString(markerLabelRes(i))).apply {
                setOnClickListener { hooks.onMarkerTap(i) }
                setOnLongClickListener {
                    hooks.onMarkerLongPress(i)
                    true
                }
            }
            markerButtons[i] = btn
            rightColumn.addView(btn, columnLp(dp(44f), ViewGroup.LayoutParams.WRAP_CONTENT, dp(6f)))
        }

        // QA Phase 5: MEDIA opens the Media Pool (MainActivity owns the dialog).
        val mediaBtn = makeColumnButton(activity.getString(R.string.btn_media)).apply {
            setOnClickListener { hooks.onMediaOpen() }
        }
        rightColumn.addView(mediaBtn, columnLp(dp(60f), ViewGroup.LayoutParams.WRAP_CONTENT, dp(8f)))

        val slateBtn = makeColumnButton(activity.getString(R.string.btn_slate)).apply {
            setOnClickListener { hooks.onSlateOpen() }
        }
        rightColumn.addView(slateBtn, columnLp(dp(60f), ViewGroup.LayoutParams.WRAP_CONTENT, dp(8f)))

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL
        )
        lp.marginEnd = dp(4f)
        root.addView(rightColumn, lp)
    }

    // -----------------------------------------------------------------------------------------
    // Zone 3 — BOTTOM: 48 dp bar (histogram | PEAK ZEBRA FC LUT GUIDES EIS | stereo VU meter)
    //          + the floating slider row directly above the bar
    // -----------------------------------------------------------------------------------------

    private fun buildBottomZone() {
        bottomZone = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        bottomSliderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        bottomZone.addView(
            bottomSliderRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = barBg()
            val pad = dp(4f)
            setPadding(pad, pad, pad, pad)
        }

        histogramView = HistogramView(activity)
        val histLp = LinearLayout.LayoutParams(dp(118f), dp(38f))
        histLp.marginStart = dp(6f)
        bottomBar.addView(histogramView, histLp)

        // Center pills group, kept centered with two weight-1 springs.
        val pillsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        peakPill = makePill(activity.getString(R.string.btn_peak)) { togglePanel(PANEL_PEAK) }
        zebraPill = makePill(activity.getString(R.string.btn_zebra)) { togglePanel(PANEL_ZEBRA) }
        fcPill = makePill(activity.getString(R.string.btn_false_color)).apply {
            setOnClickListener {
                settings.falseColorOn = !settings.falseColorOn
                refreshAll()
            }
        }
        lutPill = makePill(activity.getString(R.string.btn_lut)) { togglePanel(PANEL_LUT) }
        guidesPill = makePill(activity.getString(R.string.btn_guides)) { togglePanel(PANEL_GUIDES) }
        eisPill = makePill(activity.getString(R.string.btn_eis)).apply {
            setOnClickListener {
                settings.eisOn = !settings.eisOn
                refreshAll()
                hooks.onEisToggled(settings.eisOn)
            }
        }
        pillsRow.addView(peakPill, topPillLp())
        pillsRow.addView(zebraPill, topPillLp())
        pillsRow.addView(fcPill, topPillLp())
        pillsRow.addView(lutPill, topPillLp())
        pillsRow.addView(guidesPill, topPillLp())
        pillsRow.addView(eisPill, topPillLp())

        val springLp = LinearLayout.LayoutParams(0, 1, 1f)
        bottomBar.addView(View(activity), springLp)
        bottomBar.addView(pillsRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        bottomBar.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))

        audioMeter = AudioMeterView(activity)
        val meterLp = LinearLayout.LayoutParams(dp(118f), dp(38f))
        meterLp.marginEnd = dp(6f)
        bottomBar.addView(audioMeter, meterLp)

        bottomZone.addView(
            bottomBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)
            )
        )

        root.addView(
            bottomZone,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
    }

    /** Transient status chip (engine info lines) floating under the top zone. */
    private fun buildStatusChip() {
        statusChip = TextView(activity).apply {
            text = ""
            setTextColor(textColor())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            background = panelBg()
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
            val padH = dp(12f)
            val padV = dp(6f)
            setPadding(padH, padV, padH, padV)
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        lp.topMargin = dp(56f)
        lp.marginStart = dp(180f)
        lp.marginEnd = dp(180f)
        root.addView(statusChip, lp)
    }

    private fun installRootGestures() {
        val rootGestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = false

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleChrome()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val dy = e2.y - (e1?.y ?: e2.y)
                val dx = e2.x - (e1?.x ?: e2.x)
                if (abs(dy) > dp(80f) && abs(dy) > abs(dx) && abs(velocityY) > 400f * density) {
                    toggleChrome()
                    return true
                }
                return false
            }
        })
        // Fallback only: FocusOverlayView consumes picture-area touches, bars consume theirs.
        root.setOnTouchListener { _, event -> rootGestures.onTouchEvent(event) }
    }

    // -----------------------------------------------------------------------------------------
    // Public API (MainActivity + engine callbacks)
    // -----------------------------------------------------------------------------------------

    /** Re-render every pill label + active state from settings. */
    fun refreshAll() {
        fpsPill.text = settings.resolution.label
        shutterPill.text = settings.shutterLabel()
        irisPill.text = irisLabel()
        isoPill.text = settings.isoLabel()
        wbPill.text = settings.wbLabel()
        tintPill.text = tintLabel()
        codecPill.text = codecLabel()
        zoomPill.text = zoomLabel()

        setActiveBg(lockButton, settings.aeLock)
        setActiveBg(peakPill, settings.peakingMode != Models.PeakingMode.OFF)
        setActiveBg(zebraPill, settings.zebraMode != Models.ZebraMode.OFF)
        setActiveBg(fcPill, settings.falseColorOn)
        setActiveBg(lutPill, settings.lutPreset != Models.LutPreset.OFF)
        setActiveBg(
            guidesPill,
            settings.guide != Models.GuidePreset.OFF || settings.grid != Models.GridOverlay.NONE
        )
        setActiveBg(eisPill, settings.eisOn)
        for (i in 0..2) markerButtons[i]?.let { setActiveBg(it, settings.focusMarkers[i] != null) }

        guidesOverlay.refresh()
        focusOverlay.refreshMarkers()
        composeSystemPill()

        // The open panel re-renders from settings (chip states can never go stale).
        if (openPanelId != null) renderPanel(openPanelId!!)
    }

    /** Timecode pill; called at 10 Hz from MainActivity and on engine frame counts. */
    fun updateTimecode(frameIndex: Long, fps: Int) {
        tcPill.text = Models.formatTimecode(frameIndex.coerceAtLeast(0L), fps.coerceAtLeast(1))
    }

    /** Engine info line — transient chip under the top bar (zone law: no STATUS pill). */
    fun setStatus(text: String) {
        statusText = text.uppercase(Locale.US)
        if (statusText.isEmpty()) {
            statusChip.visibility = View.GONE
            return
        }
        statusChip.text = statusText
        statusChip.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideStatusChip)
        mainHandler.postDelayed(hideStatusChip, 3000L)
    }

    private val hideStatusChip = Runnable { statusChip.visibility = View.GONE }

    /** Tally: red dot compound drawable on the TC pill + REC button state. */
    fun setRecording(recording: Boolean, blink: Boolean) {
        setTally(recording)
        recButton.recording = recording
        recButton.blinkEnabled = blink
    }

    fun onLevels(peakL: Float, rmsL: Float, peakR: Float, rmsR: Float) {
        audioMeter.setLevels(peakL, rmsL, peakR, rmsR)
    }

    fun onHistogram(bins: ByteBuffer) {
        histogramView.onHistogram(bins)
    }

    fun onTelemetry(aeState: Int, afState: Int, flicker: Int) {
        val hints = ArrayList<String>(2)
        when (flicker) {
            1 -> hints.add("FLK 50Hz")
            2 -> hints.add("FLK 60Hz")
        }
        when (afState) {
            3, 4 -> hints.add("AF OK")
        }
        if (aeState == 4) hints.add("AE SRCH")
        telemetryHint = hints.joinToString(" ")
        composeSystemPill()
    }

    fun onSystemStatus(batteryPct: Int, tempC: Float, thermalHeadroom: Float, freeGb: Float) {
        systemStatus = String.format(
            Locale.US, "%.0f°C %d%% TH%.1f %.1fGB",
            tempC, batteryPct.coerceIn(0, 100), thermalHeadroom, freeGb
        )
        composeSystemPill()
    }

    /** Show the animated tap box at a normalized position (e.g. F-marker recall). */
    fun showTapBoxAt(nx: Float, ny: Float) {
        focusOverlay.showTapBox(nx, ny)
    }

    fun refreshMarkers() {
        focusOverlay.refreshMarkers()
    }

    /** Last viewfinder tap as [x, y] normalized, or null if the operator has not tapped yet. */
    fun lastTapPoint(): FloatArray? = lastTap?.copyOf()

    /** Called by MainActivity after a successful .cube import. */
    fun refreshAfterLut(path: String?) {
        settings.importedLutPath = path
        settings.lutPreset = Models.LutPreset.IMPORTED
        refreshAll()
    }

    fun destroy() {
        zoomRackAnim?.cancel()
        closePanel()
        mainHandler.removeCallbacksAndMessages(null)
    }

    // -----------------------------------------------------------------------------------------
    // Labels + status composition
    // -----------------------------------------------------------------------------------------

    private fun irisLabel(): String = "f/1.8"

    private fun tintLabel(): String = String.format(Locale.US, "TINT %+d", settings.tint)

    /** QA Phase 6: focal-equivalent zoom readout, e.g. "1.0x 26mm" (CONTROL_ZOOM_RATIO law). */
    private fun zoomLabel(): String =
        String.format(Locale.US, "%.1fx %dmm", settings.zoomRatio, Models.FOCAL_EQUIV_MM)

    private fun codecLabel(): String = "HEVC ${settings.bitrate.label}"

    private fun composeSystemPill() {
        val parts = ArrayList<String>(2)
        if (systemStatus.isNotEmpty()) parts.add(systemStatus)
        if (telemetryHint.isNotEmpty()) parts.add(telemetryHint)
        sysPill.text = if (parts.isEmpty()) "—" else parts.joinToString(" ")
    }

    private fun setTally(on: Boolean) {
        if (on) {
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(activity.getColor(R.color.hud_record_red))
            }
            dot.setBounds(0, 0, dp(9f), dp(9f))
            tcPill.setCompoundDrawables(null, null, dot, null)
            tcPill.compoundDrawablePadding = dp(6f)
        } else {
            tcPill.setCompoundDrawables(null, null, null, null)
        }
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        val target = if (chromeVisible) 1f else 0f
        val hide = !chromeVisible
        for (v in listOf(topZone, bottomZone, rightColumn)) {
            v.animate().cancel()
            v.animate()
                .alpha(target)
                .setDuration(180L)
                .withStartAction { v.visibility = View.VISIBLE }
                .withEndAction { if (hide) v.visibility = View.INVISIBLE }
                .start()
        }
    }

    // -----------------------------------------------------------------------------------------
    // View factories
    // -----------------------------------------------------------------------------------------

    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    private fun accentColor(): Int = activity.getColor(R.color.hud_accent)
    private fun textColor(): Int = activity.getColor(R.color.hud_text_primary)
    private fun dimColor(): Int = activity.getColor(R.color.hud_text_dim)

    private fun barBg(): GradientDrawable = GradientDrawable().apply {
        setColor(activity.getColor(R.color.hud_bg))
    }

    private fun panelBg(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(12f).toFloat()
        setColor(activity.getColor(R.color.panel_bg))
    }

    private fun pillBg(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10f).toFloat()
        setColor(activity.getColor(R.color.hud_pill_bg))
    }

    private fun makePill(
        label: String,
        mono: Boolean = false,
        sizeSp: Float = 12f,
        onClick: ((View) -> Unit)? = null
    ): TextView = TextView(activity).apply {
        text = label.uppercase(Locale.US)
        setTextColor(textColor())
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT_BOLD
        background = pillBg()
        val padH = dp(8f)
        val padV = dp(5f)
        setPadding(padH, padV, padH, padV)
        minWidth = dp(44f)
        gravity = Gravity.CENTER
        maxLines = 1
        onClick?.let { setOnClickListener(it) }
    }

    private fun makeColumnButton(label: String): TextView = TextView(activity).apply {
        text = label.uppercase(Locale.US)
        setTextColor(textColor())
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.DEFAULT_BOLD
        background = pillBg()
        val padH = dp(6f)
        val padV = dp(6f)
        setPadding(padH, padV, padH, padV)
        gravity = Gravity.CENTER
        maxLines = 1
    }

    private fun setActiveBg(v: TextView, on: Boolean) {
        (v.background as? GradientDrawable)?.setColor(
            activity.getColor(if (on) R.color.hud_pill_active else R.color.hud_pill_bg)
        )
    }

    private fun topPillLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(4f) }

    private fun columnLp(w: Int, h: Int, topMargin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply { this.topMargin = topMargin }

    private fun markerLabelRes(i: Int): Int = when (i) {
        0 -> R.string.marker_f1
        1 -> R.string.marker_f2
        else -> R.string.marker_f3
    }

    // -----------------------------------------------------------------------------------------
    // Panel machinery (CP8 overlap law: ONE panel at a time, in-layout, never a PopupWindow)
    // -----------------------------------------------------------------------------------------

    private companion object {
        const val PANEL_FPS = "fps"
        const val PANEL_SHUTTER = "shutter"
        const val PANEL_IRIS = "iris"
        const val PANEL_ISO = "iso"
        const val PANEL_WB = "wb"
        const val PANEL_TINT = "tint"
        const val PANEL_ZOOM = "zoom"
        const val PANEL_CODEC = "codec"
        const val PANEL_SYS = "sys"
        const val PANEL_PEAK = "peak"
        const val PANEL_ZEBRA = "zebra"
        const val PANEL_LUT = "lut"
        const val PANEL_GUIDES = "guides"
    }

    private class ChipItem(val label: String, val active: () -> Boolean, val onClick: () -> Unit)

    /** Tapping a pill opens its panel; tapping the open pill closes it. Every opener first
     *  closes whatever panel is open (in EITHER zone) — stacking is impossible by construction. */
    private fun togglePanel(id: String) {
        if (openPanelId == id) {
            closePanel()
        } else {
            openPanelId = id
            renderPanel(id)
        }
    }

    private fun closePanel() {
        openPanelId = null
        topSliderRow.removeAllViews()
        topSliderRow.visibility = View.GONE
        bottomSliderRow.removeAllViews()
        bottomSliderRow.visibility = View.GONE
    }

    /** Rebuilds the named panel into its zone's slider row (top params under the bar, bottom
     *  pills above the bar). */
    private fun renderPanel(id: String) {
        topSliderRow.removeAllViews()
        bottomSliderRow.removeAllViews()
        topSliderRow.visibility = View.GONE
        bottomSliderRow.visibility = View.GONE

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg()
            elevation = dp(8f).toFloat()
            val pad = dp(10f)
            setPadding(pad, pad, pad, pad)
        }
        buildPanelContent(container, id)
        if (container.childCount == 0) {
            openPanelId = null
            return
        }

        val isTop = when (id) {
            PANEL_FPS, PANEL_SHUTTER, PANEL_IRIS, PANEL_ISO, PANEL_WB, PANEL_TINT,
            PANEL_ZOOM, PANEL_CODEC, PANEL_SYS -> true
            else -> false
        }
        val host = if (isTop) topSliderRow else bottomSliderRow

        val wrap = FrameLayout(activity)
        wrap.addView(
            container,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(4f)
        lp.marginStart = dp(8f)
        lp.marginEnd = dp(84f)
        // QA Phase 6C3: bottom-hosted panels are siblings ABOVE bottomBar in bottomZone, so the
        // histogram/audio-meter scopes can never be overlapped; the 6 dp gap is separation only.
        lp.bottomMargin = dp(6f)
        host.addView(wrap, lp)
        host.visibility = View.VISIBLE
    }

    private fun buildPanelContent(container: LinearLayout, id: String) {
        when (id) {
            PANEL_FPS -> panelFps(container)
            PANEL_SHUTTER -> panelShutter(container)
            PANEL_IRIS -> panelIris(container)
            PANEL_ISO -> panelIso(container)
            PANEL_WB -> panelWb(container)
            PANEL_TINT -> panelTint(container)
            PANEL_ZOOM -> panelZoom(container)
            PANEL_CODEC -> panelCodec(container)
            PANEL_SYS -> panelSys(container)
            PANEL_PEAK -> panelPeak(container)
            PANEL_ZEBRA -> panelZebra(container)
            PANEL_LUT -> panelLut(container)
            PANEL_GUIDES -> panelGuides(container)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Panel widgets
    // -----------------------------------------------------------------------------------------

    private fun makeChip(item: ChipItem): TextView = TextView(activity).apply {
        text = item.label
        setTextColor(textColor())
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.DEFAULT_BOLD
        background = pillBg()
        val padH = dp(10f)
        val padV = dp(6f)
        setPadding(padH, padV, padH, padV)
        gravity = Gravity.CENTER
        maxLines = 1
        minWidth = dp(40f)
        setActiveBg(this, item.active())
        setOnClickListener {
            item.onClick()
            refreshAll()
        }
    }

    private fun LinearLayout.chipRow(items: List<ChipItem>): HorizontalScrollView {
        val scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for (item in items) {
            row.addView(
                makeChip(item),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6f) }
            )
        }
        scroll.addView(row)
        addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        )
        return scroll
    }

    /** The tactile per-panel reset chip (QA Phase 4): restores this parameter's clean default. */
    private fun LinearLayout.resetChip(action: () -> Unit) {
        chipRow(listOf(ChipItem(activity.getString(R.string.btn_reset), { false }, action)))
    }

    private fun LinearLayout.caption(text: String): TextView {
        val t = TextView(activity).apply {
            this.text = text.uppercase(Locale.US)
            setTextColor(dimColor())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }
        addView(
            t,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4f) }
        )
        return t
    }

    private fun LinearLayout.slider(
        label: String,
        minVal: Int,
        maxVal: Int,
        step: Int,
        initVal: Int,
        format: (Int) -> String,
        onValue: (Int) -> Unit
    ) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val lbl = TextView(activity).apply {
            text = label.uppercase(Locale.US)
            setTextColor(dimColor())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            width = dp(56f)
            maxLines = 1
        }
        row.addView(lbl)

        val seek = SeekBar(activity).apply {
            max = ((maxVal - minVal) / step.coerceAtLeast(1)).coerceAtLeast(1)
            progress = (((initVal - minVal) / step.coerceAtLeast(1)).coerceIn(0, max))
            progressTintList = ColorStateList.valueOf(accentColor())
            thumbTintList = ColorStateList.valueOf(accentColor())
        }
        val readout = TextView(activity).apply {
            setTextColor(textColor())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            width = dp(104f)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
        }
        readout.text = format((minVal + seek.progress * step).coerceIn(minVal, maxVal))

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = (minVal + progress * step).coerceIn(minVal, maxVal)
                readout.text = format(v)
                if (fromUser) onValue(v)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        row.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(readout)
        addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun LinearLayout.infoLine(text: String): TextView {
        val t = TextView(activity).apply {
            this.text = text
            setTextColor(dimColor())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            maxLines = 1
        }
        addView(
            t,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return t
    }

    private fun exposureReadout(): String {
        val eff = settings.effectiveExposureNs()
        return String.format(Locale.US, "= %.2f ms  (%.0f us)", eff / 1_000_000.0, eff / 1_000.0)
    }

    // -----------------------------------------------------------------------------------------
    // Panels
    // -----------------------------------------------------------------------------------------

    private fun panelFps(c: LinearLayout) {
        c.caption("Frame rate / format")
        c.chipRow(
            Models.ResPreset.entries.map { p ->
                ChipItem(p.label, { settings.resolution == p }, {
                    settings.resolution = p
                    refreshAll()
                    hooks.onFormatChanged(p)
                    closePanel()
                })
            }
        )
    }

    private fun panelCodec(c: LinearLayout) {
        c.caption("Codec / bitrate (rebuilds the encoder)")
        c.infoLine("c2.exynos.hevc.encoder — HEVC Main, BT.709 SDR, CFR")
        c.chipRow(
            Models.BitratePreset.entries.map { p ->
                ChipItem(p.label, { settings.bitrate == p }, {
                    settings.bitrate = p
                    refreshAll()
                    hooks.onBitrateChanged(p)
                    closePanel()
                })
            }
        )
    }

    private fun panelZoom(c: LinearLayout) {
        c.caption("Digital zoom — CONTROL_ZOOM_RATIO 1.0x-8.0x")
        c.chipRow(
            listOf(
                ChipItem("1.0x (26mm)", { abs(settings.zoomRatio - 1.0f) < 0.01f }, { applyZoom(1.0f) }),
                ChipItem("1.35x (35mm)", { abs(settings.zoomRatio - 1.35f) < 0.01f }, { applyZoom(1.35f) }),
                ChipItem("2.0x (52mm)", { abs(settings.zoomRatio - 2.0f) < 0.01f }, { applyZoom(2.0f) }),
                ChipItem("4.0x (104mm)", { abs(settings.zoomRatio - 4.0f) < 0.01f }, { applyZoom(4.0f) }),
                ChipItem(activity.getString(R.string.btn_rack_zoom), { settings.rackZoomOn }, {
                    settings.rackZoomOn = !settings.rackZoomOn
                    refreshAll()
                })
            )
        )
        c.slider(
            "Zoom", 10, 80, 1, (settings.zoomRatio * 10f).roundToInt(),
            { String.format(Locale.US, "%.1fx", it / 10f) },
            { v -> applyZoom(v / 10f) }
        )
        c.resetChip { applyZoom(Models.ZOOM_DEFAULT) }
    }

    /**
     * QA Phase 6 zoom sink: clamps to the CONTROL_ZOOM_RATIO law, drives zoomApplier (wired by
     * MainActivity to CameraEngine.setZoomRatio) and, with RACK 1.5s armed, sweeps a 1.5 s servo.
     */
    private fun applyZoom(target: Float) {
        val to = target.coerceIn(Models.ZOOM_MIN, Models.ZOOM_MAX)
        if (settings.rackZoomOn && to != settings.zoomRatio) {
            // Smooth 1.5 s servo rack (QA Phase 6): ValueAnimator sweep, zoomApplier every frame.
            zoomRackAnim?.cancel()
            val anim = ValueAnimator.ofFloat(settings.zoomRatio, to)
            anim.duration = Models.RACK_ZOOM_MS
            anim.addUpdateListener { a ->
                val z = a.animatedValue as Float
                settings.zoomRatio = z
                zoomApplier?.invoke(z)
                zoomPill.text = zoomLabel()
            }
            zoomRackAnim = anim
            anim.start()
        } else {
            settings.zoomRatio = to
            zoomApplier?.invoke(to)
        }
        refreshAll()
    }

    private fun panelShutter(c: LinearLayout) {
        c.caption("Shutter — angle / speed (angle is the invariant)")
        var angleBox: LinearLayout? = null
        var speedBox: LinearLayout? = null
        val readout = TextView(activity).apply {
            setTextColor(accentColor())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
        }

        fun syncMode() {
            val angleMode = settings.shutterMode == Models.ShutterMode.ANGLE
            angleBox?.visibility = if (angleMode) View.VISIBLE else View.GONE
            speedBox?.visibility = if (angleMode) View.GONE else View.VISIBLE
            readout.text = exposureReadout()
        }

        val angle = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val speed = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        angleBox = angle
        speedBox = speed

        c.chipRow(
            listOf(
                ChipItem("ANGLE", { settings.shutterMode == Models.ShutterMode.ANGLE }, {
                    settings.shutterMode = Models.ShutterMode.ANGLE
                    onSettingsDirty?.invoke()
                    refreshAll()
                    syncMode()
                }),
                ChipItem("SPEED", { settings.shutterMode == Models.ShutterMode.SPEED }, {
                    settings.shutterMode = Models.ShutterMode.SPEED
                    onSettingsDirty?.invoke()
                    refreshAll()
                    syncMode()
                })
            )
        )

        angle.chipRow(
            Models.shutterAnglePresets().map { a ->
                val label = if (a % 1f == 0f) "${a.toInt()}°" else "${a}°"
                ChipItem(label, {
                    settings.shutterMode == Models.ShutterMode.ANGLE &&
                        abs(settings.shutterAngleDeg - a) < 0.05f
                }, {
                    settings.shutterAngleDeg = a
                    onSettingsDirty?.invoke()
                    refreshAll()
                    readout.text = exposureReadout()
                })
            }
        )
        // QA Phase 6B3: tenths of a degree — 172.8° (24 fps, 50 Hz flicker-free) is reachable.
        angle.slider(
            "Angle", 450, 3600, 1, (settings.shutterAngleDeg * 10f).roundToInt(),
            { String.format(Locale.US, "%.1f°", it / 10f) },
            { v ->
                settings.shutterAngleDeg = v / 10f
                onSettingsDirty?.invoke()
                refreshAll()
                readout.text = exposureReadout()
            }
        )

        speed.chipRow(
            Models.shutterSpeedDenominators().map { d ->
                ChipItem("1/$d", {
                    settings.shutterMode == Models.ShutterMode.SPEED &&
                        settings.shutterSpeedDenominator == d
                }, {
                    settings.shutterSpeedDenominator = d
                    onSettingsDirty?.invoke()
                    refreshAll()
                    readout.text = exposureReadout()
                })
            }
        )

        c.addView(
            angle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        c.addView(
            speed,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        c.addView(readout)
        c.resetChip {
            settings.shutterMode = Models.ShutterMode.ANGLE
            settings.shutterAngleDeg = 180f
            onSettingsDirty?.invoke()
            refreshAll()
            readout.text = exposureReadout()
            syncMode()
        }
        syncMode()
    }

    private fun panelIris(c: LinearLayout) {
        c.caption("Iris / exposure")
        c.infoLine("Fixed aperture f/1.8 — S5KJN1 50MP (M14 hardware law)")
        if (settings.manualExposure) {
            c.infoLine("EV is locked while manual exposure drives shutter + ISO")
        } else {
            c.slider(
                "EV", (Models.EV_MIN * 10).roundToInt(), (Models.EV_MAX * 10).roundToInt(), 1,
                (settings.evCompensation * 10).roundToInt(),
                { String.format(Locale.US, "%+.1f EV", it / 10f) },
                { v ->
                    settings.evCompensation = v / 10f
                    onSettingsDirty?.invoke()
                }
            )
        }
        c.resetChip {
            settings.evCompensation = 0f
            onSettingsDirty?.invoke()
            refreshAll()
        }
    }

    private fun panelIso(c: LinearLayout) {
        c.caption("ISO (manual steals the sensor: AE OFF + direct injection)")
        c.chipRow(
            listOf(
                ChipItem("AUTO", { !settings.manualExposure }, {
                    settings.manualExposure = false
                    onSettingsDirty?.invoke()
                    refreshAll()
                }),
                ChipItem("MANUAL", { settings.manualExposure }, {
                    settings.manualExposure = true
                    onSettingsDirty?.invoke()
                    refreshAll()
                })
            )
        )
        // QA Phase 6A3: AE lock only exists while the auto engine is actually driving exposure.
        if (!settings.manualExposure) {
            c.chipRow(
                listOf(
                    ChipItem("AE LOCK", { settings.aeLock }, {
                        settings.aeLock = !settings.aeLock
                        onSettingsDirty?.invoke()
                        refreshAll()
                    })
                )
            )
        }
        if (settings.manualExposure) {
            // QA Phase 6B1: 16-stop 1/3-stop ladder — the slider indexes stops, never interpolates.
            c.slider(
                "ISO", 0, Models.isoStops().size - 1, 1, isoStopIndex(settings.iso),
                { "ISO ${Models.isoStops()[it].toInt()}" },
                { v ->
                    settings.iso = Models.isoStops()[v].toInt()
                    onSettingsDirty?.invoke()
                }
            )
        }
        c.resetChip {
            settings.manualExposure = false
            settings.aeLock = false
            onSettingsDirty?.invoke()
            refreshAll()
        }
    }

    /** Nearest ladder index for the persisted ISO (QA Phase 6B1; stops are never interpolated). */
    private fun isoStopIndex(iso: Int): Int =
        Models.isoStops().indexOfFirst { it.toInt() >= iso }.takeIf { it >= 0 } ?: 0

    private fun panelWb(c: LinearLayout) {
        c.caption("White balance")
        c.chipRow(
            Models.WbPreset.entries.map { p ->
                ChipItem(wbChipLabel(p), { settings.wbPreset == p }, {
                    settings.wbPreset = p
                    onSettingsDirty?.invoke()
                    refreshAll()
                })
            }
        )
        if (settings.wbPreset == Models.WbPreset.KELVIN) {
            // QA Phase 6B2: perceptually uniform mired space (10^6 / K), 1M step.
            c.slider(
                "Kelvin", Models.miredMin(), Models.miredMax(), 1, Models.miredFromKelvin(settings.kelvin),
                { m -> "${Models.kelvinFromMired(m)} K · ${m}M" },
                { v ->
                    settings.kelvin = Models.kelvinFromMired(v)
                    settings.wbPreset = Models.WbPreset.KELVIN
                    onSettingsDirty?.invoke()
                    refreshAll()
                }
            )
        }
        // QA Phase 6A4: AWB lock only while a scene preset drives the AWB engine (KELVIN ignores it).
        if (settings.wbPreset != Models.WbPreset.KELVIN) {
            c.chipRow(
                listOf(
                    ChipItem("AWB LOCK", { settings.awbLock }, {
                        settings.awbLock = !settings.awbLock
                        onSettingsDirty?.invoke()
                        refreshAll()
                    })
                )
            )
        }
        c.resetChip {
            settings.wbPreset = Models.WbPreset.AUTO
            settings.kelvin = 5600
            settings.tint = 0
            settings.awbLock = false
            onSettingsDirty?.invoke()
            refreshAll()
        }
    }

    private fun wbChipLabel(p: Models.WbPreset): String = when (p) {
        Models.WbPreset.AUTO -> "AUTO"
        Models.WbPreset.DAYLIGHT -> "DAYLIGHT"
        Models.WbPreset.CLOUDY_DAYLIGHT -> "CLOUDY"
        Models.WbPreset.INCANDESCENT -> "TUNGSTEN"
        Models.WbPreset.FLUORESCENT -> "FLUORESCENT"
        Models.WbPreset.KELVIN -> "KELVIN"
    }

    private fun panelTint(c: LinearLayout) {
        c.caption("Tint (CC shift, applied with manual Kelvin)")
        c.slider(
            "Tint", Models.TINT_MIN, Models.TINT_MAX, 1, settings.tint,
            { String.format(Locale.US, "%+d", it) },
            { v ->
                settings.tint = v
                if (settings.wbPreset != Models.WbPreset.KELVIN) {
                    settings.wbPreset = Models.WbPreset.KELVIN
                }
                onSettingsDirty?.invoke()
                refreshAll()
            }
        )
        c.resetChip {
            settings.tint = 0
            onSettingsDirty?.invoke()
            refreshAll()
        }
    }

    private fun panelSys(c: LinearLayout) {
        c.caption("System")
        c.infoLine(sysPill.text.toString().ifEmpty { "— " })
        c.chipRow(
            listOf(
                ChipItem("EIS", { settings.eisOn }, {
                    settings.eisOn = !settings.eisOn
                    refreshAll()
                    hooks.onEisToggled(settings.eisOn)
                }),
                ChipItem("TORCH", { settings.torchOn }, {
                    settings.torchOn = !settings.torchOn
                    refreshAll()
                    hooks.onTorchToggled(settings.torchOn)
                })
            )
        )
    }

    private fun panelPeak(c: LinearLayout) {
        c.caption("Focus peaking — 5x5 Laplacian (Blackmagic kernel)")
        c.chipRow(
            Models.PeakingMode.entries.map { m ->
                ChipItem(peakModeLabel(m), { settings.peakingMode == m }, {
                    settings.peakingMode = m
                    refreshAll()
                })
            }
        )
        c.chipRow(
            Models.PeakingColor.entries.map { k ->
                ChipItem(k.name, { settings.peakingColor == k }, {
                    settings.peakingColor = k
                    refreshAll()
                })
            }
        )
        c.slider(
            "Strength", 0, 100, 1, (settings.peakingStrength * 100f).roundToInt(),
            { String.format(Locale.US, "%.2f", it / 100f) },
            { v ->
                settings.peakingStrength = v / 100f
                refreshAll()
            }
        )
        c.resetChip {
            settings.peakingMode = Models.PeakingMode.OFF
            settings.peakingStrength = Models.PEAKING_DEFAULT_UI
            refreshAll()
        }
    }

    private fun peakModeLabel(m: Models.PeakingMode): String = when (m) {
        Models.PeakingMode.OFF -> "OFF"
        Models.PeakingMode.COLORED_LINES -> "COLORED"
        Models.PeakingMode.PEAK -> "PEAK"
    }

    private fun panelZebra(c: LinearLayout) {
        c.caption("Zebras — diagonal marching stripes, mid-grey bands")
        c.chipRow(
            listOf(
                ChipItem("OFF", { settings.zebraMode == Models.ZebraMode.OFF }, {
                    settings.zebraMode = Models.ZebraMode.OFF
                    refreshAll()
                }),
                ChipItem("95%", { settings.zebraMode == Models.ZebraMode.Z95 }, {
                    settings.zebraMode = Models.ZebraMode.Z95
                    refreshAll()
                }),
                ChipItem("90%", { settings.zebraMode == Models.ZebraMode.Z90 }, {
                    settings.zebraMode = Models.ZebraMode.Z90
                    refreshAll()
                }),
                ChipItem("MID-GREY", { settings.zebraMode == Models.ZebraMode.MID_GREY }, {
                    settings.zebraMode = Models.ZebraMode.MID_GREY
                    refreshAll()
                }),
                ChipItem("MG+1", { settings.zebraMode == Models.ZebraMode.MID_GREY_PLUS1 }, {
                    settings.zebraMode = Models.ZebraMode.MID_GREY_PLUS1
                    refreshAll()
                })
            )
        )
        if (settings.zebraMode == Models.ZebraMode.Z95 || settings.zebraMode == Models.ZebraMode.Z90) {
            // QA Phase 6B4: the readout is the raw UI threshold — the ×0.93 709 term lives in GL.
            c.slider(
                "Thresh", 75, 100, 1, (settings.zebraThresholdUi * 100f).roundToInt(),
                { v -> String.format(Locale.US, "%.2f", v / 100f) },
                { v -> settings.zebraThresholdUi = v / 100f }
            )
        }
        c.resetChip {
            settings.zebraMode = Models.ZebraMode.OFF
            settings.zebraThresholdUi = Models.ZEBRA_DEFAULT_UI_THRESHOLD
            refreshAll()
        }
    }

    private fun panelLut(c: LinearLayout) {
        c.caption("Look — 33^3 film-emulation 3D LUT")
        c.chipRow(
            listOf(
                ChipItem("OFF", { settings.lutPreset == Models.LutPreset.OFF }, { selectLut(Models.LutPreset.OFF) }),
                ChipItem("709 SOFT", { settings.lutPreset == Models.LutPreset.NEUTRAL_709 }, { selectLut(Models.LutPreset.NEUTRAL_709) }),
                ChipItem("TEAL-ORG", { settings.lutPreset == Models.LutPreset.TEAL_ORANGE }, { selectLut(Models.LutPreset.TEAL_ORANGE) }),
                ChipItem("KODAK 2383", { settings.lutPreset == Models.LutPreset.KODAK_2383 }, { selectLut(Models.LutPreset.KODAK_2383) }),
                ChipItem("ETERNA", { settings.lutPreset == Models.LutPreset.ETERNA }, { selectLut(Models.LutPreset.ETERNA) }),
                ChipItem("WARM FADE", { settings.lutPreset == Models.LutPreset.WARM_FADE }, { selectLut(Models.LutPreset.WARM_FADE) }),
                ChipItem("PAN B&W", { settings.lutPreset == Models.LutPreset.MONO_CONTRAST }, { selectLut(Models.LutPreset.MONO_CONTRAST) }),
                ChipItem("IMPORT", { settings.lutPreset == Models.LutPreset.IMPORTED }, {
                    closePanel()
                    val requester = lutImportRequester
                    if (requester != null) {
                        requester()
                    } else {
                        setStatus("LUT import unavailable")
                    }
                })
            )
        )
        c.resetChip { selectLut(Models.LutPreset.OFF) }
    }

    private fun selectLut(p: Models.LutPreset) {
        settings.lutPreset = p
        val path = if (p == Models.LutPreset.IMPORTED) settings.importedLutPath else null
        lutApplier?.invoke(p, path)
        refreshAll()
    }

    private fun panelGuides(c: LinearLayout) {
        c.caption("Aspect guide")
        c.chipRow(
            Models.GuidePreset.entries.map { g ->
                ChipItem(g.label, { settings.guide == g }, {
                    settings.guide = g
                    refreshAll()
                })
            }
        )
        c.caption("Grid")
        c.chipRow(
            listOf(
                ChipItem("NONE", { settings.grid == Models.GridOverlay.NONE }, {
                    settings.grid = Models.GridOverlay.NONE
                    refreshAll()
                }),
                ChipItem("THIRDS", { settings.grid == Models.GridOverlay.THIRDS }, {
                    settings.grid = Models.GridOverlay.THIRDS
                    refreshAll()
                }),
                ChipItem("CROSSHAIR", { settings.grid == Models.GridOverlay.CROSSHAIR }, {
                    settings.grid = Models.GridOverlay.CROSSHAIR
                    refreshAll()
                }),
                ChipItem("HORIZON", { settings.grid == Models.GridOverlay.HORIZON }, {
                    settings.grid = Models.GridOverlay.HORIZON
                    refreshAll()
                })
            )
        )
        c.caption("Anamorphic de-squeeze")
        c.chipRow(
            Models.DesqueezePreset.entries.map { d ->
                ChipItem(d.label, { settings.desqueeze == d }, {
                    settings.desqueeze = d
                    refreshAll()
                })
            }
        )
        // QA Phase 2: operator 180° preview flip (rig / DOF-adapter mounts render inverted).
        c.chipRow(
            listOf(
                ChipItem(activity.getString(R.string.btn_flip), { settings.previewFlip180 }, {
                    settings.previewFlip180 = !settings.previewFlip180
                })
            )
        )
        c.resetChip {
            settings.guide = Models.GuidePreset.OFF
            settings.grid = Models.GridOverlay.NONE
            settings.desqueeze = Models.DesqueezePreset.X100
            settings.previewFlip180 = false
            refreshAll()
        }
    }

    // -----------------------------------------------------------------------------------------
    // REC button (idle: solid red circle / recording: white ring + blinking red core)
    // -----------------------------------------------------------------------------------------

    private class RecButton(context: Context) : View(context) {

        var recording: Boolean = false
            set(v) {
                field = v
                invalidate()
            }

        var blinkEnabled: Boolean = true

        private val density = resources.displayMetrics.density
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }

        init {
            contentDescription = context.getString(R.string.btn_record)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) / 2f - 2f * density
            val red = context.getColor(R.color.hud_record_red)

            if (!recording) {
                fillPaint.color = red
                fillPaint.alpha = 255
                canvas.drawCircle(cx, cy, r, fillPaint)
            } else {
                canvas.drawCircle(cx, cy, r, ringPaint)
                val blinkOn = !blinkEnabled || (SystemClock.uptimeMillis() / 500L) % 2L == 0L
                fillPaint.color = red
                fillPaint.alpha = if (blinkOn) 255 else 70
                canvas.drawCircle(cx, cy, r - 5f * density, fillPaint)
                fillPaint.alpha = 255
                postInvalidateDelayed(500L)
            }
        }
    }
}
