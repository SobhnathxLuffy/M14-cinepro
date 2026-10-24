package com.m14.cinepro.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.util.Log
import com.m14.cinepro.color.ColorScience
import com.m14.cinepro.core.Models
import com.m14.cinepro.core.SettingsStore

private const val TAG = "M14Cam"

/**
 * M14 CinePro — white-balance control (stage3_camera2.md §2.5 + §3, QA Phase 4 single-stage law).
 *
 * Preset → CONTROL_AWB_MODE map (hardware AWB path):
 *   AUTO→1, DAYLIGHT→5, CLOUDY_DAYLIGHT→6, INCANDESCENT→2, FLUORESCENT→3.
 *
 * KELVIN path (QA Phase 4): the M14 HAL does NOT accept COLOR_CORRECTION_GAINS (missing from
 * availableRequestKeys — raw_camera_dump.txt), so hardware manual gains are useless and AWB_OFF
 * only lets the ISP hunt. The request side therefore LOCKS the ISP to AWB_MODE_DAYLIGHT (5600 K
 * reference) with COLOR_CORRECTION_MODE_FAST and writes NO gains; the Kelvin/Tint color is applied
 * exactly once, in the GL shader, via [normalizedShaderGains] →
 * ColorScience.kelvinShaderGainsRelative (5600 K-relative, so 5600 K + tint 0 == identity).
 *
 * Preset path (§2.5 l()): AWB_MODE = preset value, AWB_LOCK = settings.awbLock,
 *   COLOR_CORRECTION_MODE_FAST(1) — hardware AWB, shader trim is identity unless tint != 0.
 *
 * tint != 0 under a hardware preset → shader-only green-magenta trim via
 * ColorScience.tintShaderGain (single stage; never combined with request-side gains).
 *
 * The measured CCM (§3.7 rh0) is still cached from TotalCaptureResult.COLOR_CORRECTION_TRANSFORM
 * while the device AWB is running (CONTROL_AWB_MODE != OFF), so shader-relative Kelvin gain
 * generation always uses the freshest per-lens color matrix.
 */
class WhiteBalanceController(private val settings: SettingsStore) {

    private val ccmLock = Any()

    /** Last AWB-measured camera color matrix (row-major 3x3 floats); identity until measured. */
    private var measuredCcm: FloatArray = ColorScience.identityCcm()

    /** Preset → Camera2 CONTROL_AWB_MODE value. */
    fun awbCamera2Mode(): Int = when (settings.wbPreset) {
        Models.WbPreset.AUTO -> CaptureRequest.CONTROL_AWB_MODE_AUTO                     // 1
        Models.WbPreset.DAYLIGHT -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT            // 5
        Models.WbPreset.CLOUDY_DAYLIGHT -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT // 6
        Models.WbPreset.INCANDESCENT -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT    // 2
        Models.WbPreset.FLUORESCENT -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT      // 3
        // Unreachable via [apply] under the QA Phase 4 law (KELVIN is locked to DAYLIGHT there);
        // kept so the preset map stays complete for any direct caller.
        Models.WbPreset.KELVIN -> CaptureRequest.CONTROL_AWB_MODE_OFF                   // 0
    }

    /**
     * Apply the WB request state onto [builder]. [ccm] is accepted for the established call
     * contract (CameraEngine forwards its cached value each rebuild) but is no longer consumed by
     * the request path under the QA Phase 4 single-stage law — no gains are ever written; the
     * shader math reads the cache through [currentCcm].
     */
    fun apply(builder: CaptureRequest.Builder, ccm: FloatArray) {
        if (settings.wbPreset == Models.WbPreset.KELVIN) {
            // QA Phase 4 single-stage law: the ISP is LOCKED to DAYLIGHT (5600 K reference) so it
            // never hunts, and the GL shader applies the relative Kelvin/Tint gains
            // (ColorScience.kelvinShaderGainsRelative). The M14 HAL ignores manual
            // COLOR_CORRECTION_GAINS (not in availableRequestKeys), so no gains are written.
            builder.guardedSet(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT)
            builder.guardedSet(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
        } else {
            builder.guardedSet(CaptureRequest.CONTROL_AWB_MODE, awbCamera2Mode())
            builder.guardedSet(CaptureRequest.CONTROL_AWB_LOCK, settings.awbLock)
            builder.guardedSet(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
        }
    }

    /**
     * Cache the AWB-measured CCM from a capture result (stage3 §3.7): only while the device AWB is
     * running (CONTROL_AWB_MODE != OFF), so the shader-relative gain math never consumes a matrix
     * captured under a locked/modified pipeline.
     */
    fun onCaptureResult(result: TotalCaptureResult) {
        val awbMode = try {
            result.get(CaptureResult.CONTROL_AWB_MODE)
        } catch (t: Throwable) {
            Log.w("M14_CINEPRO", "AWB mode read failed", t)
            null
        } ?: return
        if (awbMode == CaptureRequest.CONTROL_AWB_MODE_OFF) return

        val transform = try {
            result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        } catch (t: Throwable) {
            Log.w("M14_CINEPRO", "CCM transform read failed", t)
            null
        } ?: return

        val floats = transformToFloats(transform) ?: return
        synchronized(ccmLock) {
            if (!floats.contentEquals(measuredCcm)) measuredCcm = floats
        }
    }

    /** Copy of the currently cached measured CCM (identity until the HAL publishes one). */
    fun currentCcm(): FloatArray = synchronized(ccmLock) { measuredCcm.copyOf() }

    /**
     * Normalized [r, g, b] shader gains for the GL viewfinder WB trim — the SINGLE color stage
     * (QA Phase 4): the request side never carries gains, so the shader owns the whole Kelvin/Tint
     * look. KELVIN → ColorScience.kelvinShaderGainsRelative (5600 K-normalized: 5600 K + tint 0 is
     * exactly (1,1,1)); hardware presets → identity while tint == 0, otherwise the
     * green-magenta-only trim [1, tintShaderGain(tint), 1].
     */
    fun normalizedShaderGains(): FloatArray {
        if (settings.wbPreset == Models.WbPreset.KELVIN) {
            return ColorScience.kelvinShaderGainsRelative(
                settings.kelvin.coerceIn(Models.KELVIN_MIN, Models.KELVIN_MAX),
                settings.tint.coerceIn(Models.TINT_MIN, Models.TINT_MAX),
                currentCcm()
            )
        }
        if (settings.tint == 0) return floatArrayOf(1f, 1f, 1f)
        val g = ColorScience.tintShaderGain(settings.tint)
        return floatArrayOf(1f, g, 1f)
    }

    /** Extract 9 floats from a ColorSpaceTransform (Rational num/den pairs → float). */
    private fun transformToFloats(transform: ColorSpaceTransform): FloatArray? {
        return try {
            val packed = IntArray(18)
            transform.copyElements(packed, 0)
            FloatArray(9) { i ->
                val den = packed[i * 2 + 1]
                if (den == 0) 0f else packed[i * 2].toFloat() / den.toFloat()
            }
        } catch (e: Exception) {
            Log.w(TAG, "CCM result read failed", e)
            null
        }
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
