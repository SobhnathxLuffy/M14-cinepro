package com.m14.cinepro.camera

import android.graphics.Rect
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.SystemClock
import android.util.Log
import com.m14.cinepro.core.Models
import com.m14.cinepro.core.SettingsStore

private const val TAG = "M14Cam"

/** Tap/marker metering weight — 0x3e8 law (stage3_camera2.md §2.6, ah0.e). */
private const val METERING_WEIGHT = 1000

/** Delay before the repeating request flips CONTROL_AF_TRIGGER back to IDLE (task law ~150 ms). */
private const val AF_TRIGGER_IDLE_DELAY_MS = 150L

/** A tap-focus hold lasts this long before CONTINUOUS_PICTURE is restored automatically. */
private const val AF_TAP_HOLD_MS = 5_000L

/** Shared zero-weight sentinel region used to "clear" AF/AE regions (stage3 §2.6 ah0.a). */
private val ZERO_REGION = arrayOf(MeteringRectangle(Rect(0, 0, 0, 0), 0))

/**
 * M14 CinePro — focus control.
 *
 * M14 HARD RULES (M14_VERIFIED_TRUTH.md §4.D — violating them = immediate onCaptureFailed reason 0):
 *  - NEVER set CONTROL_AF_MODE_OFF(0): the HAL rejects it (available AF modes are [1, 3, 4] only).
 *  - NEVER set LENS_FOCUS_DISTANCE: the driver aborts the frame (CAPTURE_FAILURE_REASON_ERROR).
 *  - Only CONTROL_AF_MODE_AUTO(1) / CONTINUOUS_VIDEO(3) / CONTINUOUS_PICTURE(4) are ever written.
 *  - NEVER NOISE_REDUCTION_MODE_OFF(0); NOISE_REDUCTION_MODE_FAST(1) is applied on every request
 *    this controller touches.
 *
 * Tap-to-focus is a two-phase state machine (stage3 §2.6 C()):
 *  1. [startTapFocus] arms AF_MODE_AUTO + AF_TRIGGER_START + 1000-weight AF/AE metering regions
 *     (plus AE_MODE_ON + AE_PRECAPTURE_TRIGGER_START when AE is not manually overridden).
 *  2. The next request built ≥150 ms later (CameraEngine's applySettings cycle / delayed reset)
 *     flips the trigger to IDLE while keeping the ROI, so the HAL converges and reports
 *     FOCUSED_LOCKED.
 *  3. [onAfResult] (fed by CameraEngine from CaptureResult.CONTROL_AF_STATE) returns true when the
 *     tap sequence must move on; CameraEngine then refreshes the repeating request. NOT_FOCUSED_LOCKED
 *     and a 5 s hold both fall back to CONTINUOUS_PICTURE so CAF can never wedge off.
 *
 * F-markers (settings.focusMarkers, normalized viewfinder coords) are honored as persistent AF/AE
 * metering regions while in CONTINUOUS phase — the only meaningful "marker focus" on M14, where
 * LENS_FOCUS_DISTANCE is HAL-blocked.
 */
class FocusController(private val settings: SettingsStore) {

    data class FocusPoint(val normalizedX: Float, val normalizedY: Float)

    private enum class Phase { CONTINUOUS, TAP_START, TAP_ACTIVE }

    @Volatile private var phase = Phase.CONTINUOUS
    @Volatile private var tapRegions: Array<MeteringRectangle>? = null
    private var tapStartedAtMs = 0L

    /**
     * Map a viewfinder tap (normalized in the upright, display-rotated viewfinder, y down) into a
     * sensor-space [MeteringRectangle] with weight 1000.
     *
     * Mapping: (1) the HAL center-crops the active array to the viewfinder aspect — compute that
     * visible window; (2) undo the sensor mount rotation ([sensorOrientation] in {0, 90, 180, 270})
     * to invert the display rotation; (3) place a ~1/8-of-visible-window rect centered on the tap,
     * clamped inside [activeArray].
     */
    fun meteringFor(
        point: FocusPoint,
        activeArray: Rect,
        sensorOrientation: Int,
        viewfinderW: Int,
        viewfinderH: Int
    ): Array<MeteringRectangle> {
        val aw = activeArray.width()
        val ah = activeArray.height()
        if (aw <= 0 || ah <= 0) {
            return arrayOf(MeteringRectangle(Rect(0, 0, 0, 0), METERING_WEIGHT))
        }

        // (1) visible (aspect-cropped) window of the active array.
        val vfAspect = if (viewfinderH > 0) {
            viewfinderW.toFloat() / viewfinderH.toFloat()
        } else {
            Models.VIEWFINDER_WIDTH.toFloat() / Models.VIEWFINDER_HEIGHT.toFloat()
        }
        val arrayAspect = aw.toFloat() / ah.toFloat()
        var visW: Float
        var visH: Float
        if (arrayAspect > vfAspect) {
            visH = ah.toFloat()
            visW = ah.toFloat() * vfAspect
        } else {
            visW = aw.toFloat()
            visH = aw.toFloat() / vfAspect
        }
        if (visW <= 0f || visH <= 0f) {
            visW = aw.toFloat()
            visH = ah.toFloat()
        }
        val visLeft = (aw - visW) * 0.5f
        val visTop = (ah - visH) * 0.5f

        // (2) display -> sensor rotation (viewfinder is shown upright; undo the mount rotation).
        val nx = point.normalizedX.coerceIn(0f, 1f)
        val ny = point.normalizedY.coerceIn(0f, 1f)
        val sx: Float
        val sy: Float
        when (((sensorOrientation % 360) + 360) % 360) {
            90 -> { sx = ny; sy = 1f - nx }
            180 -> { sx = 1f - nx; sy = 1f - ny }
            270 -> { sx = 1f - ny; sy = nx }
            else -> { sx = nx; sy = ny }
        }
        val cx = visLeft + sx * visW
        val cy = visTop + sy * visH

        // (3) tap region ≈ 1/8 of the visible window (half-extent = 1/16).
        val halfW = (visW / 16f).coerceAtLeast(1f)
        val halfH = (visH / 16f).coerceAtLeast(1f)
        val left = (cx - halfW).toInt().coerceIn(activeArray.left, activeArray.right - 1)
        val top = (cy - halfH).toInt().coerceIn(activeArray.top, activeArray.bottom - 1)
        val right = (cx + halfW).toInt().coerceIn(left + 1, activeArray.right)
        val bottom = (cy + halfH).toInt().coerceIn(top + 1, activeArray.bottom)
        return arrayOf(MeteringRectangle(Rect(left, top, right, bottom), METERING_WEIGHT))
    }

    /**
     * Arm a tap-focus cycle onto [builder] and record the internal START phase. CameraEngine calls
     * this from the camera thread right before pushing the repeating request; the next request
     * built ≥150 ms later resets the trigger to IDLE (see [apply]).
     */
    fun startTapFocus(point: FocusPoint, activeArray: Rect, sensorOrientation: Int, builder: CaptureRequest.Builder) {
        val regions = meteringFor(point, activeArray, sensorOrientation, Models.VIEWFINDER_WIDTH, Models.VIEWFINDER_HEIGHT)
        tapRegions = regions
        tapStartedAtMs = SystemClock.elapsedRealtime()
        phase = Phase.TAP_START

        builder.guardedSet(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        builder.guardedSet(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        builder.guardedSet(CaptureRequest.CONTROL_AF_REGIONS, regions)
        // AE follows the tap ROI (stage3 §2.6 C()); skipped under manual exposure to protect the
        // stealth manual-exposure law (AE_MODE_OFF must not be flipped by a focus tap).
        if (!settings.manualExposure) {
            builder.guardedSet(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.guardedSet(CaptureRequest.CONTROL_AE_LOCK, false)
            builder.guardedSet(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            builder.guardedSet(CaptureRequest.CONTROL_AE_REGIONS, regions)
        }
        builder.guardedSet(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
    }

    /**
     * Explicit reset to continuous autofocus: AF_MODE_CONTINUOUS_PICTURE, AF_TRIGGER_IDLE and the
     * zero-weight sentinel region. Internal state is reset too, so the next [apply] keeps CAF.
     */
    fun restoreContinuous(builder: CaptureRequest.Builder) {
        phase = Phase.CONTINUOUS
        tapRegions = null
        builder.guardedSet(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        builder.guardedSet(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        builder.guardedSet(CaptureRequest.CONTROL_AF_REGIONS, ZERO_REGION)
        builder.guardedSet(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
    }

    /**
     * Base application for every rebuilt repeating request.
     *  - NOISE_REDUCTION_MODE_FAST always (M14 hard rule).
     *  - TAP_START (fresh tap, <150 ms): re-asserts AF_MODE_AUTO + AF_TRIGGER_START + ROI.
     *  - TAP_ACTIVE: AF_MODE_AUTO + AF_TRIGGER_IDLE + ROI (HAL converges and locks).
     *  - CONTINUOUS: AF_MODE_CONTINUOUS_PICTURE + AF regions from pending F-markers, else the
     *    zero-weight sentinel region.
     */
    fun apply(builder: CaptureRequest.Builder, activeArray: Rect?, sensorOrientation: Int) {
        builder.guardedSet(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)

        if (phase == Phase.TAP_START && SystemClock.elapsedRealtime() - tapStartedAtMs >= AF_TRIGGER_IDLE_DELAY_MS) {
            phase = Phase.TAP_ACTIVE
        }

        when (phase) {
            Phase.TAP_START -> {
                builder.guardedSet(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.guardedSet(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                tapRegions?.let {
                    builder.guardedSet(CaptureRequest.CONTROL_AF_REGIONS, it)
                    if (!settings.manualExposure) builder.guardedSet(CaptureRequest.CONTROL_AE_REGIONS, it)
                }
                if (!settings.manualExposure) {
                    builder.guardedSet(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.guardedSet(CaptureRequest.CONTROL_AE_LOCK, false)
                    builder.guardedSet(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                }
            }
            Phase.TAP_ACTIVE -> {
                if (SystemClock.elapsedRealtime() - tapStartedAtMs >= AF_TAP_HOLD_MS) {
                    // Tap hold expired — never wedge CAF off indefinitely.
                    phase = Phase.CONTINUOUS
                    tapRegions = null
                    applyContinuous(builder, activeArray, sensorOrientation)
                } else {
                    builder.guardedSet(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    builder.guardedSet(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                    builder.guardedSet(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
                    tapRegions?.let {
                        builder.guardedSet(CaptureRequest.CONTROL_AF_REGIONS, it)
                        if (!settings.manualExposure) builder.guardedSet(CaptureRequest.CONTROL_AE_REGIONS, it)
                    }
                }
            }
            Phase.CONTINUOUS -> applyContinuous(builder, activeArray, sensorOrientation)
        }
    }

    /**
     * Feed CaptureResult.CONTROL_AF_STATE (called by CameraEngine from its capture callback).
     *
     * @return true when the controller changed phase and the caller must refresh the repeating
     *         request (converged during START → flip trigger to IDLE; failed lock → restore CAF).
     */
    fun onAfResult(afState: Int): Boolean {
        return when (afState) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ->
                if (phase == Phase.TAP_START) {
                    phase = Phase.TAP_ACTIVE
                    true
                } else {
                    false
                }
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ->
                if (phase != Phase.CONTINUOUS) {
                    phase = Phase.CONTINUOUS
                    tapRegions = null
                    true
                } else {
                    false
                }
            else -> false
        }
    }

    private fun applyContinuous(builder: CaptureRequest.Builder, activeArray: Rect?, sensorOrientation: Int) {
        builder.guardedSet(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        builder.guardedSet(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        builder.guardedSet(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
        val regions = markerRegions(activeArray, sensorOrientation)
        if (regions != null) {
            builder.guardedSet(CaptureRequest.CONTROL_AF_REGIONS, regions)
            if (!settings.manualExposure) builder.guardedSet(CaptureRequest.CONTROL_AE_REGIONS, regions)
        } else {
            builder.guardedSet(CaptureRequest.CONTROL_AF_REGIONS, ZERO_REGION)
        }
    }

    /** Metering rectangles for every set F-marker (settings.focusMarkers), or null when none. */
    private fun markerRegions(activeArray: Rect?, sensorOrientation: Int): Array<MeteringRectangle>? {
        if (activeArray == null) return null
        val points = ArrayList<FocusPoint>(3)
        for (marker in settings.focusMarkers) {
            if (marker != null && marker.size >= 2) points.add(FocusPoint(marker[0], marker[1]))
        }
        if (points.isEmpty()) return null
        val out = ArrayList<MeteringRectangle>(points.size)
        for (p in points) {
            out.addAll(
                meteringFor(p, activeArray, sensorOrientation, Models.VIEWFINDER_WIDTH, Models.VIEWFINDER_HEIGHT)
            )
        }
        return out.toTypedArray()
    }
}

/** Sanitized request-key law: a rejected key must never crash request construction. */
private fun <T> CaptureRequest.Builder.guardedSet(key: CaptureRequest.Key<T>, value: T) {
    try {
        set(key, value)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "request key rejected by platform: ${key.name}", e)
    }
}
