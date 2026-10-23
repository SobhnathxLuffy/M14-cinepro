package com.m14.cinepro.hud

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
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.Space
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
 * M14 CinePro — programmatic landscape cinema HUD.
 *
 * Layered into the activity's [R.id.hud_root] FrameLayout (the GL viewfinder sits at index 0,
 * added by MainActivity):
 *
 *   [1] GuidesOverlayView   — aspect masks / grids / de-squeeze annotation
 *   [2] FocusOverlayView    — tap box animation + F1/F2/F3 markers, owns viewfinder gestures
 *   [3] Top bar             — FORMAT BITRATE SHUTTER ISO WB TIMECODE STATUS pills
 *   [4] Bottom container    — histogram + audio meter row, then toggle pill bar
 *   [5] Transport strip     — REC / SLATE / EIS / TORCH
 *
 * Every pill opens a popup panel (PopupWindow, rounded dark cinema style). All chrome hides
 * with a fast vertical swipe or a double tap on the picture. TAG "M14Hud".
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
        fun onFocusTap(nx: Float, ny: Float)               // normalized viewfinder coords
        fun onMarkerTap(index: Int)                        // 0..2 short tap -> AF at marker
        fun onMarkerLongPress(index: Int)                  // save last tap point as marker
    }

    // -----------------------------------------------------------------------------------------
    // Optional engine wiring (installed by MainActivity; HUD never touches engine classes)
    // -----------------------------------------------------------------------------------------

    /** MainActivity opens the framework document picker for .cube import. */
    var lutImportRequester: (() -> Unit)? = null

    /** MainActivity forwards to GlViewfinder.applyLut(preset, path). */
    var lutApplier: ((Models.LutPreset, String?) -> Unit)? = null

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
    private lateinit var topBar: LinearLayout
    private lateinit var bottomContainer: LinearLayout
    private lateinit var transportStrip: LinearLayout
    private lateinit var histogramView: HistogramView
    private lateinit var audioMeter: AudioMeterView
    private lateinit var recButton: RecButton

    private lateinit var formatPill: TextView
    private lateinit var bitratePill: TextView
    private lateinit var shutterPill: TextView
    private lateinit var isoPill: TextView
    private lateinit var wbPill: TextView
    private lateinit var tcPill: TextView
    private lateinit var statusPill: TextView
    private lateinit var peakPill: TextView
    private lateinit var zebraPill: TextView
    private lateinit var fcPill: TextView
    private lateinit var lutPill: TextView
    private lateinit var guidesPill: TextView
    private lateinit var squeezePill: TextView
    private lateinit var eisPill: TextView
    private lateinit var torchPill: TextView
    private val markerPills = arrayOfNulls<TextView>(3)

    private var popup: PopupWindow? = null
    private val panelChips = ArrayList<ChipRef>()
    private var chromeVisible = true
    private var lastTap: FloatArray? = null

    private var actionStatus = "—"
    private var systemStatus = ""
    private var telemetryHint = ""

    // -----------------------------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------------------------

    init {
        buildOverlays()
        buildTopBar()
        buildBottomContainer()
        buildTransportStrip()
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
    }

    private fun buildTopBar() {
        topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = barBg()
            val pad = dp(6f)
            setPadding(pad, pad, pad, pad)
        }

        formatPill = makePill(settings.resolution.label) { panelFormat(it) }
        bitratePill = makePill(settings.bitrate.label) { panelBitrate(it) }
        shutterPill = makePill(settings.shutterLabel()) { panelShutter(it) }
        isoPill = makePill(settings.isoLabel()) { panelIso(it) }
        wbPill = makePill(settings.wbLabel()) { panelWb(it) }
        tcPill = makePill("00:00:00:00", mono = true, sizeSp = 14f, onClick = null)
        statusPill = makePill("—", onClick = null).apply {
            gravity = Gravity.CENTER or Gravity.CENTER_VERTICAL
            ellipsize = TextUtils.TruncateAt.END
        }

        topBar.addView(formatPill, pillLp())
        topBar.addView(bitratePill, pillLp())
        topBar.addView(shutterPill, pillLp())
        topBar.addView(isoPill, pillLp())
        topBar.addView(wbPill, pillLp())
        topBar.addView(tcPill, pillLp())
        topBar.addView(
            statusPill,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        root.addView(
            topBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
            )
        )
    }

    private fun buildBottomContainer() {
        bottomContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Scopes row: histogram bottom-left, audio meter bottom-right, above the toggle bar.
        val scopesRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        histogramView = HistogramView(activity)
        val histLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        histLp.marginStart = dp(10f)
        histLp.bottomMargin = dp(10f)
        scopesRow.addView(histogramView, histLp)

        scopesRow.addView(
            Space(activity),
            LinearLayout.LayoutParams(0, 1, 1f)
        )

        audioMeter = AudioMeterView(activity)
        val meterLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        meterLp.marginEnd = dp(10f)
        meterLp.bottomMargin = dp(10f)
        scopesRow.addView(audioMeter, meterLp)

        bottomContainer.addView(
            scopesRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // Toggle pill bar.
        val pillsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = barBg()
            val pad = dp(6f)
            setPadding(pad, pad, pad, pad)
        }

        peakPill = makePill(activity.getString(R.string.btn_peak)) { panelPeak(it) }
        zebraPill = makePill(activity.getString(R.string.btn_zebra)) { panelZebra(it) }
        fcPill = makePill(activity.getString(R.string.btn_false_color)).apply {
            setOnClickListener {
                settings.falseColorOn = !settings.falseColorOn
                refreshAll()
            }
        }
        lutPill = makePill(activity.getString(R.string.btn_lut)) { panelLut(it) }
        guidesPill = makePill(activity.getString(R.string.btn_guides)) { panelGuides(it) }
        squeezePill = makePill(activity.getString(R.string.btn_desqueeze)) { panelSqueeze(it) }

        pillsRow.addView(peakPill, pillLp())
        pillsRow.addView(zebraPill, pillLp())
        pillsRow.addView(fcPill, pillLp())
        pillsRow.addView(lutPill, pillLp())
        pillsRow.addView(guidesPill, pillLp())
        pillsRow.addView(squeezePill, pillLp())

        for (i in 0..2) {
            val pill = makePill(activity.getString(markerLabelRes(i))).apply {
                setOnClickListener { hooks.onMarkerTap(i) }
                setOnLongClickListener {
                    hooks.onMarkerLongPress(i)
                    true
                }
            }
            markerPills[i] = pill
            pillsRow.addView(pill, pillLp())
        }

        bottomContainer.addView(
            pillsRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            bottomContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
    }

    private fun buildTransportStrip() {
        transportStrip = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = dp(12f)
            setPadding(pad, pad, pad, pad)
        }

        recButton = RecButton(activity).apply {
            setOnClickListener { hooks.onRecordToggle() }
        }
        transportStrip.addView(
            recButton,
            verticalStripLp(dp(76f), dp(76f), 0)
        )

        val slateBtn = TextView(activity).apply {
            text = activity.getString(R.string.btn_slate)
            setTextColor(activity.getColor(R.color.hud_text_primary))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = pillBg().apply { cornerRadius = dp(12f).toFloat() }
            maxLines = 1
            setOnClickListener { hooks.onSlateOpen() }
        }
        transportStrip.addView(slateBtn, verticalStripLp(dp(56f), dp(56f), dp(14f)))

        eisPill = makePill(activity.getString(R.string.btn_eis)).apply {
            setOnClickListener {
                settings.eisOn = !settings.eisOn
                refreshAll()
                hooks.onEisToggled(settings.eisOn)
            }
        }
        torchPill = makePill(activity.getString(R.string.btn_torch)).apply {
            setOnClickListener {
                settings.torchOn = !settings.torchOn
                refreshAll()
                hooks.onTorchToggled(settings.torchOn)
            }
        }
        transportStrip.addView(eisPill, verticalStripLp(dp(64f), ViewGroup.LayoutParams.WRAP_CONTENT, dp(14f)))
        transportStrip.addView(torchPill, verticalStripLp(dp(64f), ViewGroup.LayoutParams.WRAP_CONTENT, dp(12f)))

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL
        )
        lp.marginEnd = dp(24f)
        root.addView(transportStrip, lp)
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
        formatPill.text = settings.resolution.label
        bitratePill.text = settings.bitrate.label
        shutterPill.text = settings.shutterLabel()
        isoPill.text = settings.isoLabel()
        wbPill.text = settings.wbLabel()

        setActiveBg(peakPill, settings.peakingMode != Models.PeakingMode.OFF)
        setActiveBg(zebraPill, settings.zebraMode != Models.ZebraMode.OFF)
        setActiveBg(fcPill, settings.falseColorOn)
        setActiveBg(lutPill, settings.lutPreset != Models.LutPreset.OFF)
        setActiveBg(
            guidesPill,
            settings.guide != Models.GuidePreset.OFF || settings.grid != Models.GridOverlay.NONE
        )
        setActiveBg(squeezePill, settings.desqueeze != Models.DesqueezePreset.X100)
        for (i in 0..2) markerPills[i]?.let { setActiveBg(it, settings.focusMarkers[i] != null) }
        setActiveBg(eisPill, settings.eisOn)
        setActiveBg(torchPill, settings.torchOn)

        guidesOverlay.refresh()
        focusOverlay.refreshMarkers()
        composeStatus()
        syncPanelChips()
    }

    /** Timecode pill; called at 10 Hz from MainActivity and on engine frame counts. */
    fun updateTimecode(frameIndex: Long, fps: Int) {
        tcPill.text = Models.formatTimecode(frameIndex.coerceAtLeast(0L), fps.coerceAtLeast(1))
    }

    /** Primary status text ("READY", "REC A001C001", engine info lines). */
    fun setStatus(text: String) {
        actionStatus = text.uppercase(Locale.US).ifEmpty { "—" }
        composeStatus()
    }

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
        composeStatus()
    }

    fun onSystemStatus(batteryPct: Int, tempC: Float, thermalHeadroom: Float, freeGb: Float) {
        systemStatus = String.format(
            Locale.US, "%d%% %.0f°C TH%.1f %.1fGB",
            batteryPct.coerceIn(0, 100), tempC, thermalHeadroom, freeGb
        )
        composeStatus()
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
        popup?.dismiss()
        popup = null
        panelChips.clear()
        mainHandler.removeCallbacksAndMessages(null)
    }

    // -----------------------------------------------------------------------------------------
    // Status composition
    // -----------------------------------------------------------------------------------------

    private fun composeStatus() {
        val parts = ArrayList<String>(3)
        if (actionStatus.isNotEmpty()) parts.add(actionStatus)
        if (systemStatus.isNotEmpty()) parts.add(systemStatus)
        if (telemetryHint.isNotEmpty()) parts.add(telemetryHint)
        statusPill.text = parts.joinToString("  ")
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
        for (v in listOf(topBar, bottomContainer, transportStrip)) {
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
        sizeSp: Float = 13f,
        onClick: ((View) -> Unit)? = null
    ): TextView = TextView(activity).apply {
        text = label.uppercase(Locale.US)
        setTextColor(textColor())
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT_BOLD
        background = pillBg()
        val padH = dp(10f)
        val padV = dp(6f)
        setPadding(padH, padV, padH, padV)
        minWidth = dp(54f)
        gravity = Gravity.CENTER
        maxLines = 1
        onClick?.let { setOnClickListener(it) }
    }

    private fun setActiveBg(v: TextView, on: Boolean) {
        (v.background as? GradientDrawable)?.setColor(
            activity.getColor(if (on) R.color.hud_pill_active else R.color.hud_pill_bg)
        )
    }

    private fun pillLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(6f) }

    private fun sectionLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10f) }

    private fun verticalStripLp(w: Int, h: Int, topMargin: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).apply { this.topMargin = topMargin }

    private fun markerLabelRes(i: Int): Int = when (i) {
        0 -> R.string.marker_f1
        1 -> R.string.marker_f2
        else -> R.string.marker_f3
    }

    // -----------------------------------------------------------------------------------------
    // Popup panel machinery
    // -----------------------------------------------------------------------------------------

    private class ChipRef(val view: TextView, val provider: () -> Boolean)

    private class SliderRowRef(val row: LinearLayout, val seek: SeekBar, val readout: TextView)

    private class ChipItem(val label: String, val active: () -> Boolean, val onClick: () -> Unit)

    private fun syncPanelChips() {
        for (c in panelChips) setActiveBg(c.view, c.provider())
    }

    private fun showPanel(anchor: View, build: LinearLayout.() -> Unit) {
        popup?.dismiss()
        panelChips.clear()

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(14f)
            setPadding(pad, pad, pad, pad)
            build()
        }
        val wrapper = FrameLayout(activity)
        wrapper.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val maxW = min(dp(680f), (root.width * 0.9f).toInt().coerceAtLeast(dp(280f)))
        val pw = PopupWindow(wrapper, maxW, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(panelBg())
            isOutsideTouchable = true
            elevation = dp(10f).toFloat()
        }
        popup = pw

        val widthSpec = View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        wrapper.measure(widthSpec, heightSpec)

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val x = (loc[0] + anchor.width / 2 - maxW / 2)
            .coerceIn(dp(8f), (root.width - maxW - dp(8f)).coerceAtLeast(dp(8f)))
        val y = if (loc[1] < root.height / 2) {
            loc[1] + anchor.height + dp(6f)
        } else {
            (loc[1] - wrapper.measuredHeight - dp(6f)).coerceAtLeast(dp(8f))
        }
        pw.showAtLocation(root, Gravity.NO_GRAVITY, x, y)
    }

    /** Section caption inside a panel. */
    private fun LinearLayout.title(text: String) {
        val t = TextView(activity).apply {
            this.text = text.uppercase(Locale.US)
            setTextColor(dimColor())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(2f)
        lp.bottomMargin = dp(6f)
        addView(t, lp)
    }

    private fun makeChip(item: ChipItem): TextView = TextView(activity).apply {
        text = item.label
        setTextColor(textColor())
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        background = pillBg()
        val padH = dp(12f)
        val padV = dp(8f)
        setPadding(padH, padV, padH, padV)
        gravity = Gravity.CENTER
        maxLines = 1
        minWidth = dp(48f)
        setActiveBg(this, item.active())
        setOnClickListener {
            item.onClick()
            syncPanelChips()
        }
    }

    /** Horizontally scrollable chip group (member extension on the panel content). */
    private fun LinearLayout.chipGroup(items: List<ChipItem>) {
        val scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for (item in items) row.addView(makeChip(item), chipLp())
        scroll.addView(row)
        addView(scroll, sectionLp())
    }

    private fun chipLp(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8f) }

    /** Labelled seek row with live readout; integer domain [minVal, maxVal] with [step]. */
    private fun LinearLayout.slider(
        label: String,
        minVal: Int,
        maxVal: Int,
        step: Int,
        initVal: Int,
        format: (Int) -> String,
        onValue: (Int) -> Unit
    ): SliderRowRef {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val lbl = TextView(activity).apply {
            text = label.uppercase(Locale.US)
            setTextColor(dimColor())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            width = dp(64f)
            maxLines = 1
        }
        row.addView(lbl)

        val seek = SeekBar(activity).apply {
            max = ((maxVal - minVal) / step.coerceAtLeast(1)).coerceAtLeast(1)
            progress = ((initVal - minVal) / step.coerceAtLeast(1)).coerceIn(0, max)
            progressTintList = ColorStateList.valueOf(accentColor())
            thumbTintList = ColorStateList.valueOf(accentColor())
        }
        val readout = TextView(activity).apply {
            setTextColor(textColor())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            width = dp(112f)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
        }
        val initV = (minVal + seek.progress * step).coerceIn(minVal, maxVal)
        readout.text = format(initV)

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
        addView(row, sectionLp())
        return SliderRowRef(row, seek, readout)
    }

    private fun exposureReadout(): String {
        val eff = settings.effectiveExposureNs()
        return String.format(Locale.US, "≈ %.2f ms  (%.0f µs)", eff / 1_000_000.0, eff / 1_000.0)
    }

    // -----------------------------------------------------------------------------------------
    // Panels
    // -----------------------------------------------------------------------------------------

    private fun panelFormat(anchor: View) = showPanel(anchor) {
        title("Recording format")
        chipGroup(
            Models.ResPreset.entries.map { p ->
                ChipItem(p.label, { settings.resolution == p }, {
                    settings.resolution = p
                    refreshAll()
                    hooks.onFormatChanged(p)
                    popup?.dismiss()
                })
            }
        )
    }

    private fun panelBitrate(anchor: View) = showPanel(anchor) {
        title("Bitrate")
        chipGroup(
            Models.BitratePreset.entries.map { p ->
                ChipItem(p.label, { settings.bitrate == p }, {
                    settings.bitrate = p
                    refreshAll()
                    hooks.onBitrateChanged(p)
                    popup?.dismiss()
                })
            }
        )
    }

    private fun panelShutter(anchor: View) = showPanel(anchor) {
        title("Shutter")
        val angleBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val speedBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val readout = TextView(activity).apply {
            setTextColor(accentColor())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
        }
        var modeBtn: TextView? = null

        fun syncMode() {
            val angleMode = settings.shutterMode == Models.ShutterMode.ANGLE
            modeBtn?.text = if (angleMode) {
                "MODE: ANGLE °  ⇄  SPEED 1/x"
            } else {
                "MODE: SPEED 1/x  ⇄  ANGLE °"
            }
            angleBox.visibility = if (angleMode) View.VISIBLE else View.GONE
            speedBox.visibility = if (angleMode) View.GONE else View.VISIBLE
            readout.text = exposureReadout()
        }

        val btn = TextView(activity).apply {
            setTextColor(textColor())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            background = pillBg()
            val padH = dp(12f)
            val padV = dp(8f)
            setPadding(padH, padV, padH, padV)
            gravity = Gravity.CENTER
            setOnClickListener {
                settings.shutterMode = if (settings.shutterMode == Models.ShutterMode.ANGLE) {
                    Models.ShutterMode.SPEED
                } else {
                    Models.ShutterMode.ANGLE
                }
                syncMode()
                onSettingsDirty?.invoke()
                refreshAll()
            }
        }
        modeBtn = btn

        // Angle mode: preset chips + continuous angle slider.
        angleBox.chipGroup(
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
        angleBox.slider(
            "Angle", 45, 360, 1, settings.shutterAngleDeg.roundToInt(),
            { "$it°" },
            { v ->
                settings.shutterAngleDeg = v.toFloat()
                onSettingsDirty?.invoke()
                refreshAll()
                readout.text = exposureReadout()
            }
        )

        // Speed mode: 1/x denominator chips.
        speedBox.chipGroup(
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

        addView(btn, sectionLp())
        addView(angleBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        addView(speedBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        addView(readout, sectionLp())
        syncMode()
    }

    private fun panelIso(anchor: View) = showPanel(anchor) {
        title("Exposure — ISO / EV")
        var isoRow: SliderRowRef? = null
        var evRow: SliderRowRef? = null

        fun syncVis() {
            val manual = settings.manualExposure
            isoRow?.row?.visibility = if (manual) View.VISIBLE else View.GONE
            evRow?.row?.visibility = if (manual) View.GONE else View.VISIBLE
        }

        chipGroup(
            listOf(
                ChipItem("AUTO", { !settings.manualExposure }, {
                    settings.manualExposure = false
                    onSettingsDirty?.invoke()
                    refreshAll()
                    syncVis()
                }),
                ChipItem("MANUAL", { settings.manualExposure }, {
                    settings.manualExposure = true
                    onSettingsDirty?.invoke()
                    refreshAll()
                    syncVis()
                }),
                ChipItem("AE LOCK", { settings.aeLock }, {
                    settings.aeLock = !settings.aeLock
                    onSettingsDirty?.invoke()
                    refreshAll()
                })
            )
        )

        isoRow = slider(
            "ISO", Models.ISO_MIN, Models.ISO_MAX, 100, settings.iso,
            { "ISO $it" },
            { v ->
                settings.iso = v
                onSettingsDirty?.invoke()
                refreshAll()
            }
        )

        evRow = slider(
            "EV", (Models.EV_MIN * 10).roundToInt(), (Models.EV_MAX * 10).roundToInt(), 1,
            (settings.evCompensation * 10).roundToInt(),
            { String.format(Locale.US, "%+.1f EV", it / 10f) },
            { v ->
                settings.evCompensation = v / 10f
                onSettingsDirty?.invoke()
                refreshAll()
            }
        )

        syncVis()
    }

    private fun panelWb(anchor: View) = showPanel(anchor) {
        title("White balance")
        var kelvinRow: SliderRowRef? = null
        var tintRow: SliderRowRef? = null

        fun syncVis() {
            val kelvin = settings.wbPreset == Models.WbPreset.KELVIN
            kelvinRow?.row?.visibility = if (kelvin) View.VISIBLE else View.GONE
            tintRow?.row?.visibility = if (kelvin) View.VISIBLE else View.GONE
        }

        chipGroup(
            Models.WbPreset.entries.map { p ->
                ChipItem(wbChipLabel(p), { settings.wbPreset == p }, {
                    settings.wbPreset = p
                    onSettingsDirty?.invoke()
                    refreshAll()
                    syncVis()
                })
            }
        )

        kelvinRow = slider(
            "Kelvin", Models.KELVIN_MIN, Models.KELVIN_MAX, 50, settings.kelvin,
            { "$it K" },
            { v ->
                settings.kelvin = v
                if (settings.wbPreset != Models.WbPreset.KELVIN) {
                    settings.wbPreset = Models.WbPreset.KELVIN
                }
                onSettingsDirty?.invoke()
                refreshAll()
            }
        )

        tintRow = slider(
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

        chipGroup(
            listOf(
                ChipItem("AWB LOCK", { settings.awbLock }, {
                    settings.awbLock = !settings.awbLock
                    onSettingsDirty?.invoke()
                    refreshAll()
                })
            )
        )

        syncVis()
    }

    private fun wbChipLabel(p: Models.WbPreset): String = when (p) {
        Models.WbPreset.AUTO -> "AUTO"
        Models.WbPreset.DAYLIGHT -> "DAYLIGHT"
        Models.WbPreset.CLOUDY_DAYLIGHT -> "CLOUDY"
        Models.WbPreset.INCANDESCENT -> "TUNGSTEN"
        Models.WbPreset.FLUORESCENT -> "FLUORESCENT"
        Models.WbPreset.KELVIN -> "KELVIN"
    }

    private fun panelPeak(anchor: View) = showPanel(anchor) {
        title("Focus peaking")
        chipGroup(
            Models.PeakingMode.entries.map { m ->
                ChipItem(peakModeLabel(m), { settings.peakingMode == m }, {
                    settings.peakingMode = m
                    refreshAll()
                })
            }
        )
        chipGroup(
            Models.PeakingColor.entries.map { c ->
                ChipItem(c.name, { settings.peakingColor == c }, {
                    settings.peakingColor = c
                    refreshAll()
                })
            }
        )
        slider(
            "Strength", 0, 100, 1, (settings.peakingStrength * 100f).roundToInt(),
            { String.format(Locale.US, "%.2f", it / 100f) },
            { v ->
                settings.peakingStrength = v / 100f
                refreshAll()
            }
        )
    }

    private fun peakModeLabel(m: Models.PeakingMode): String = when (m) {
        Models.PeakingMode.OFF -> "OFF"
        Models.PeakingMode.COLORED_LINES -> "COLORED"
        Models.PeakingMode.PEAK -> "PEAK"
    }

    private fun panelZebra(anchor: View) = showPanel(anchor) {
        title("Zebras")
        var thresholdRow: SliderRowRef? = null

        fun syncVis() {
            val z = settings.zebraMode
            thresholdRow?.row?.visibility =
                if (z == Models.ZebraMode.Z95 || z == Models.ZebraMode.Z90) View.VISIBLE else View.GONE
        }

        chipGroup(
            listOf(
                ChipItem("OFF", { settings.zebraMode == Models.ZebraMode.OFF }, {
                    settings.zebraMode = Models.ZebraMode.OFF
                    refreshAll()
                }),
                ChipItem("95%", { settings.zebraMode == Models.ZebraMode.Z95 }, {
                    settings.zebraMode = Models.ZebraMode.Z95
                    refreshAll()
                    syncVis()
                }),
                ChipItem("90%", { settings.zebraMode == Models.ZebraMode.Z90 }, {
                    settings.zebraMode = Models.ZebraMode.Z90
                    refreshAll()
                    syncVis()
                }),
                ChipItem("MID-GREY", { settings.zebraMode == Models.ZebraMode.MID_GREY }, {
                    settings.zebraMode = Models.ZebraMode.MID_GREY
                    refreshAll()
                    syncVis()
                }),
                ChipItem("MG+1", { settings.zebraMode == Models.ZebraMode.MID_GREY_PLUS1 }, {
                    settings.zebraMode = Models.ZebraMode.MID_GREY_PLUS1
                    refreshAll()
                    syncVis()
                })
            )
        )

        thresholdRow = slider(
            "Thresh", 75, 100, 1, (settings.zebraThresholdUi * 100f).roundToInt(),
            { v ->
                String.format(
                    Locale.US, "%.2f → %.2f",
                    v / 100f, v / 100f * Models.ZEBRA_THRESHOLD_SCALE_709
                )
            },
            { v -> settings.zebraThresholdUi = v / 100f }
        )

        syncVis()
    }

    private fun panelLut(anchor: View) = showPanel(anchor) {
        title("Look — 3D LUT")
        chipGroup(
            listOf(
                ChipItem("OFF", { settings.lutPreset == Models.LutPreset.OFF }, {
                    selectLut(Models.LutPreset.OFF)
                }),
                ChipItem("NEUTRAL", { settings.lutPreset == Models.LutPreset.NEUTRAL_709 }, {
                    selectLut(Models.LutPreset.NEUTRAL_709)
                }),
                ChipItem("TEAL-ORG", { settings.lutPreset == Models.LutPreset.TEAL_ORANGE }, {
                    selectLut(Models.LutPreset.TEAL_ORANGE)
                }),
                ChipItem("WARM", { settings.lutPreset == Models.LutPreset.WARM_FADE }, {
                    selectLut(Models.LutPreset.WARM_FADE)
                }),
                ChipItem("MONO", { settings.lutPreset == Models.LutPreset.MONO_CONTRAST }, {
                    selectLut(Models.LutPreset.MONO_CONTRAST)
                }),
                ChipItem("IMPORT", { settings.lutPreset == Models.LutPreset.IMPORTED }, {
                    popup?.dismiss()
                    val requester = lutImportRequester
                    if (requester != null) {
                        requester()
                    } else {
                        setStatus("LUT import unavailable")
                    }
                })
            )
        )
    }

    private fun selectLut(p: Models.LutPreset) {
        settings.lutPreset = p
        val path = if (p == Models.LutPreset.IMPORTED) settings.importedLutPath else null
        lutApplier?.invoke(p, path)
        refreshAll()
        popup?.dismiss()
    }

    private fun panelGuides(anchor: View) = showPanel(anchor) {
        title("Aspect guide")
        chipGroup(
            Models.GuidePreset.entries.map { g ->
                ChipItem(g.label, { settings.guide == g }, {
                    settings.guide = g
                    refreshAll()
                })
            }
        )
        title("Grid")
        chipGroup(
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
    }

    private fun panelSqueeze(anchor: View) = showPanel(anchor) {
        title("Anamorphic de-squeeze")
        chipGroup(
            Models.DesqueezePreset.entries.map { d ->
                ChipItem(d.label, { settings.desqueeze == d }, {
                    settings.desqueeze = d
                    refreshAll()
                    popup?.dismiss()
                })
            }
        )
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
