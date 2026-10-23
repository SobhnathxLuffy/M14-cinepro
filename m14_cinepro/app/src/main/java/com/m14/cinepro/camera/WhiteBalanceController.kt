package com.m14.cinepro.camera

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.util.Log
import android.util.Rational
import com.m14.cinepro.color.ColorScience
import com.m14.cinepro.core.Models
import com.m14.cinepro.core.SettingsStore

private const val TAG = "M14Cam"

/**
 * M14 CinePro — white-balance control (stage3_camera2.md §2.5 + §3).
 *
 * Preset → CONTROL_AWB_MODE map:
 *   AUTO→1, DAYLIGHT→5, CLOUDY_DAYLIGHT→6, INCANDESCENT→2, FLUORESCENT→3, KELVIN→0 (AWB OFF).
 *
 * KELVIN path (§2.5 s() + §3 dt0 math): AWB_MODE_OFF, AWB_LOCK=false,
 *   COLOR_CORRECTION_MODE_CANCEL(0), then gains = ColorScience.kelvinToRggb(kelvin, tint, ccm);
 *   when non-null: COLOR_CORRECTION_GAINS = gains and COLOR_CORRECTION_TRANSFORM = the CCM as a
 *   ColorSpaceTransform built from Rational(round(f*1000), 1000). When the gain computation
 *   overflows (null result) the previously applied gains stay untouched — ground-truth law.
 *
 * Auto path (§2.5 l()): AWB_MODE = preset value, AWB_LOCK = settings.awbLock,
 *   COLOR_CORRECTION_MODE_FAST(1).
 *
 * The measured CCM (§3.7 rh0) is cached from TotalCaptureResult.COLOR_CORRECTION_TRANSFORM while
 * the device AWB is running (CONTROL_AWB_MODE != OFF), so manual Kelvin gain generation always
 * uses the freshest per-lens color matrix.
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
        Models.WbPreset.KELVIN -> CaptureRequest.CONTROL_AWB_MODE_OFF                   // 0
    }

    /**
     * Apply the WB state onto [builder]. [ccm] is the CCM used for the Kelvin gain math — pass
     * [currentCcm] (CameraEngine forwards its cached value each rebuild).
     */
    fun apply(builder: CaptureRequest.Builder, ccm: FloatArray) {
        if (settings.wbPreset == Models.WbPreset.KELVIN) {
            builder.guardedSet(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.guardedSet(CaptureRequest.CONTROL_AWB_LOCK, false)
            // stage3 §2.5 s(): COLOR_CORRECTION_MODE = 0 ("CANCEL" in the BMD smali; the public
            // API-34 constant for value 0 is COLOR_CORRECTION_MODE_TRANSFORM_MATRIX — the raw
            // metadata value 0 is the operative law, so it is written literally).
            builder.guardedSet(CaptureRequest.COLOR_CORRECTION_MODE, 0)

            val matrix = if (ccm.size == 9) ccm else ColorScience.identityCcm()
            val gains = try {
                ColorScience.kelvinToRggb(
                    settings.kelvin.coerceIn(Models.KELVIN_MIN, Models.KELVIN_MAX),
                    settings.tint.coerceIn(Models.TINT_MIN, Models.TINT_MAX),
                    matrix
                )
            } catch (e: Exception) {
                Log.w(TAG, "kelvin->gains computation failed", e)
                null
            }
            if (gains != null) {
                builder.guardedSet(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
                ccmToTransform(matrix)?.let {
                    builder.guardedSet(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it)
                }
            }
            // gains == null → leave COLOR_CORRECTION_GAINS untouched (keep last applied gains).
        } else {
            builder.guardedSet(CaptureRequest.CONTROL_AWB_MODE, awbCamera2Mode())
            builder.guardedSet(CaptureRequest.CONTROL_AWB_LOCK, settings.awbLock)
            builder.guardedSet(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
        }
    }

    /**
     * Cache the AWB-measured CCM from a capture result (stage3 §3.7): only while the device AWB is
     * running (CONTROL_AWB_MODE != OFF), so Kelvin-mode injections never pollute the cache.
     */
    fun onCaptureResult(result: TotalCaptureResult) {
        val awbMode = try {
            result.get(CaptureResult.CONTROL_AWB_MODE)
        } catch (t: Throwable) {
            null
        } ?: return
        if (awbMode == CaptureRequest.CONTROL_AWB_MODE_OFF) return

        val transform = try {
            result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        } catch (t: Throwable) {
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
     * Normalized [r, 1, b] shader gains for the GL viewfinder WB trim: identity while any AWB
     * preset runs (the HAL has already white-balanced the preview), ColorScience-derived
     * green-normalized gains for the Kelvin path.
     */
    fun normalizedShaderGains(): FloatArray {
        if (settings.wbPreset != Models.WbPreset.KELVIN) return floatArrayOf(1f, 1f, 1f)
        return ColorScience.kelvinToNormalizedRgbGains(
            settings.kelvin.coerceIn(Models.KELVIN_MIN, Models.KELVIN_MAX),
            settings.tint.coerceIn(Models.TINT_MIN, Models.TINT_MAX),
            currentCcm()
        )
    }

    /**
     * ColorSpaceTransform from a row-major 3x3 float matrix via Rational(round(f*1000), 1000)
     * (equivalent to the 18-int num/den packed form). Null when the matrix is malformed.
     */
    private fun ccmToTransform(ccm: FloatArray): ColorSpaceTransform? {
        if (ccm.size != 9) return null
        return try {
            ColorSpaceTransform(Array(9) { i -> Rational(Math.round(ccm[i] * 1000f), 1000) })
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "CCM transform build failed", e)
            null
        }
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
