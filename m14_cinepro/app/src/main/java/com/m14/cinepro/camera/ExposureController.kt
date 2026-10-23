package com.m14.cinepro.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import com.m14.cinepro.core.Models
import com.m14.cinepro.core.SettingsStore

private const val TAG = "M14Cam"

/** Default EV compensation step (1/3 EV) when the HAL does not report CONTROL_AE_COMPENSATION_STEP. */
private const val DEFAULT_EV_STEP = 1f / 3f

/**
 * M14 CinePro — manual/auto exposure CaptureRequest application (stage3_camera2.md §2.1–2.4).
 *
 * Manual path ([SettingsStore.manualExposure] == true) implements the verified "stealth unlock"
 * (M14_VERIFIED_TRUTH.md §4.D): with CONTROL_AE_MODE_OFF the Exynos 1330 HAL executes
 * SENSOR_EXPOSURE_TIME / SENSOR_SENSITIVITY directly (30 µs..100 ms, ISO 100..3200), even though
 * both keys are missing from the sanitized `availableRequestKeys` table of Camera 0.
 *
 * - SENSOR_EXPOSURE_TIME  = settings.effectiveExposureNs() (110 µs headroom-snapped by Models),
 *                           additionally clamped to SENSOR_INFO_EXPOSURE_TIME_RANGE.
 * - SENSOR_SENSITIVITY    = settings.iso clamped to the 100..3200 law, then to
 *                           SENSOR_INFO_SENSITIVITY_RANGE.
 * - SENSOR_FRAME_DURATION = 1e9 / fps (stage3 §2.1/§2.4 pacing law; the HAL may silently ignore
 *                           it — VERIFIED_TRUTH B2 — so it is never treated as fatal).
 *
 * Auto path (§2.3): CONTROL_AE_MODE_ON + CONTROL_AE_LOCK + EV compensation converted to steps
 * via `round(ev / step)` clamped to CONTROL_AE_COMPENSATION_RANGE.
 *
 * Every key set goes through [guardedSet] (catches IllegalArgumentException) so an unavailable
 * key can never crash request construction (M14 sanitized request-key law).
 */
class ExposureController(private val settings: SettingsStore) {

    /** Capability snapshot read once at camera-open from [CameraCharacteristics]. */
    data class CameraCaps(
        val exposureRange: Range<Long>?,
        val isoRange: Range<Int>?,
        val evStep: Float,
        val evSteps: Range<Int>?,
        val antibandingModes: IntArray,
        val maxFrameDurationNs: Long
    )

    /**
     * Read the exposure-relevant capability set from the camera characteristics.
     * Every individual read is guarded; a missing key degrades to null / safe default.
     */
    fun readCaps(characteristics: CameraCharacteristics): CameraCaps {
        val exposureRange = readKey(characteristics, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = readKey(characteristics, CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val evSteps = readKey(characteristics, CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val stepRational = readKey(characteristics, CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        var evStep = stepRational?.toFloat() ?: DEFAULT_EV_STEP
        if (evStep <= 0f || evStep.isNaN() || evStep.isInfinite()) evStep = DEFAULT_EV_STEP
        val antibandingModes = readKey(
            characteristics,
            CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES
        ) ?: IntArray(0)
        val maxFrameDurationNs = readKey(
            characteristics,
            CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION
        ) ?: 0L
        return CameraCaps(exposureRange, isoRange, evStep, evSteps, antibandingModes, maxFrameDurationNs)
    }

    /**
     * Apply the current exposure state onto [builder]. [fps] is the recording frame rate
     * (settings.fps) used for the SENSOR_FRAME_DURATION pacing law; [caps] may be null when the
     * characteristics have not been read yet (all clamps then fall back to the Models law).
     */
    fun apply(builder: CaptureRequest.Builder, caps: CameraCaps?, fps: Int) {
        val fpsSafe = if (fps > 0) fps else 30
        val frameDurationNs = 1_000_000_000L / fpsSafe

        if (settings.manualExposure) {
            // stage3 §2.1 manual law — AE OFF + direct sensor injection (stealth manual exposure).
            builder.guardedSet(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            var exposureNs = settings.effectiveExposureNs()
            caps?.exposureRange?.let { exposureNs = clampLong(exposureNs, it) }
            builder.guardedSet(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)

            var iso = settings.iso.coerceIn(Models.ISO_MIN, Models.ISO_MAX)
            caps?.isoRange?.let { iso = clampInt(iso, it) }
            builder.guardedSet(CaptureRequest.SENSOR_SENSITIVITY, iso)

            // Frame pacing: HAL may silently ignore (VERIFIED_TRUTH B2) — requested, never fatal.
            builder.guardedSet(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
        } else {
            // stage3 §2.3 auto path: AE ON + lock + EV compensation in integer steps.
            builder.guardedSet(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.guardedSet(CaptureRequest.CONTROL_AE_LOCK, settings.aeLock)
            if (caps != null) {
                val steps = caps.evSteps
                if (steps != null && caps.evStep > 0f) {
                    val rawSteps = Math.round(settings.evCompensation / caps.evStep)
                    builder.guardedSet(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampInt(rawSteps, steps))
                }
            }
            // stage3 §2.4 fps pacing law: 1e9/fps is requested in AE-ON as well.
            builder.guardedSet(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
        }

        // Antibanding only when the mode is advertised by the HAL (stage3 §2.10 h()).
        val abMode = settings.antiBanding.camera2Value
        val modes = caps?.antibandingModes
        if (modes == null || modes.contains(abMode)) {
            builder.guardedSet(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, abMode)
        }
    }

    private fun <T> readKey(characteristics: CameraCharacteristics, key: CameraCharacteristics.Key<T>): T? =
        try {
            characteristics.get(key)
        } catch (t: Throwable) {
            null
        }

    private fun clampLong(value: Long, range: Range<Long>): Long {
        val lo = minOf(range.lower, range.upper)
        val hi = maxOf(range.lower, range.upper)
        return value.coerceIn(lo, hi)
    }

    private fun clampInt(value: Int, range: Range<Int>): Int {
        val lo = minOf(range.lower, range.upper)
        val hi = maxOf(range.lower, range.upper)
        return value.coerceIn(lo, hi)
    }
}

/**
 * Sanitized request-key law: setting a key the platform/HAL does not accept must throw
 * IllegalArgumentException at most into this guard — never up into request construction.
 */
private fun <T> CaptureRequest.Builder.guardedSet(key: CaptureRequest.Key<T>, value: T) {
    try {
        set(key, value)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "request key rejected by platform: ${key.name}", e)
    }
}
