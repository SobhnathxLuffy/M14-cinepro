package com.m14.cinepro.color

import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * M14 CinePro — exact clean-room port of the Blackmagic color-science math
 * (`dt0.java` + `ColorScienceUtils` + `libcolor_science.so`, reconstructed verbatim in
 * docs/stage3_camera2.md section 3).
 *
 * Law set (do not "improve" any constant — these are the verified ground truth):
 *  - Planckian locus (CIE 1960 UCS), rational polynomials in T:
 *      u_p(T) = (1.28641212e-7·T² + 1.54118254e-4·T + 0.860117757) / (7.08145163e-7·T² + 8.42420235e-4·T + 1.0)
 *      v_p(T) = (4.20481691e-8·T² + 4.22806245e-5·T + 0.317398726) / (1.61456053e-7·T² − 2.89741816e-5·T + 1.0)
 *  - CIE 1960 -> CIE 1931: x = 3u/(2u−8v+4), y = 2v/(2u−8v+4); white X = x/y, Z = (1−x−y)/y.
 *  - XYZ -> linear Rec.709 via the exact sRGB/Rec.709 matrix (Y normalized to 1).
 *  - Tint = green-gain offset around the geometric mean: gG = sqrt(gR·gB)·e^(−tint/100)
 *    (QA Phase 4: the Blackmagic deviceTintOffset = −130 is REMOVED — on the Samsung S5KJN1
 *    sensor it halved green at neutral tint and inflated red/blue by 1.92x).
 *  - Gains normalized so min(gR,gG,gB) >= 1.
 *  - 10-iteration mired refinement loop (98% error correction, mired clamped [40,1000]).
 *  - Reverse CCT: golden-section search, 30 iterations, invphi = 0.618033988749895, mired [40,1000],
 *    minimizing squared uv distance to the locus; degenerate chromaticity falls back to 5000 K.
 *  - Reverse tint: tint = clamp(−200·ln(gG / sqrt(gR·gB)) − 130, −50, +50); the 0.72 green sensor
 *    baseline factor applies ONLY to the CCT estimate input, never to the tint formula.
 */
object ColorScience {

    private const val INVPHI = 0.618033988749895
    private const val MIRED_MIN = 40.0
    private const val MIRED_MAX = 1000.0
    private const val GREEN_BASELINE = 0.72f

    /** Kelvin reference for the relative shader gains: the M14 ISP daylight lock is 5600 K. */
    const val REFERENCE_KELVIN = 5600

    /** Shader tint trim divisor (QA Phase 4 law): gGain = exp(−tint/150), gentle + symmetric. */
    private const val TINT_SHADER_DIVISOR = 150.0f

    /** Hard clamp for every shader wbGain channel (QA Phase 4: viewfinder can never go black/neon). */
    const val SHADER_GAIN_MIN = 0.60f
    const val SHADER_GAIN_MAX = 1.60f

    /** Identity 3x3 CCM (row-major), used when the HAL has not published a measured matrix. */
    fun identityCcm(): FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )

    // -------------------------------------------------------------------------------------
    // Planckian locus — CIE 1960 UCS rational polynomials (kz2.p(T2, a, b, c) = c / (a·T2 + b))
    // -------------------------------------------------------------------------------------

    private fun planckianU(T: Double): Double {
        val T2 = T * T
        val num = (1.28641212e-7 * T2) + (1.54118254e-4 * T) + 0.860117757
        val den = (7.08145163e-7 * T2) + (8.42420235e-4 * T) + 1.0
        return num / den
    }

    private fun planckianV(T: Double): Double {
        val T2 = T * T
        val num = (4.20481691e-8 * T2) + (4.22806245e-5 * T) + 0.317398726
        val den = (1.61456053e-7 * T2) - (2.89741816e-5 * T) + 1.0
        return num / den
    }

    /** Squared uv distance to the Planckian locus at the given mired (dt0.f). */
    private fun locusDistance(u: Double, v: Double, mired: Double): Double {
        val T = 1_000_000.0 / mired
        val du = u - planckianU(T)
        val dv = v - planckianV(T)
        return du * du + dv * dv
    }

    // -------------------------------------------------------------------------------------
    // FORWARD: (kelvin, tint, ccm) -> RggbChannelVector   (dt0.d)
    // -------------------------------------------------------------------------------------

    /**
     * Compute camera white-balance gains for a Kelvin/Tint pair.
     *
     * @param kelvin target correlated color temperature, 2500..10000 K (UI law, stage3 §7d)
     * @param tint   green-magenta axis, −50..+50
     * @param ccm    row-major 3x3 camera color matrix (measured CCM preferred, else identity)
     * @return gains with both greens equal, normalized so the minimum gain >= 1,
     *         or null when a gain overflows (the caller keeps its previous gains — ground-truth law).
     */
    fun kelvinToRggb(kelvin: Int, tint: Int, ccm: FloatArray): RggbChannelVector? {
        val inv = inverse3x3(ccm)
        val fExp = exp(-(tint / 100.0f)).toFloat()
        val targetMired = 1_000_000.0 / kelvin
        var mired = targetMired
        var gR = 1f
        var gG = 1f
        var gB = 1f
        for (i in 0 until 10) {
            val T = 1_000_000.0 / mired
            val uP = planckianU(T)
            val vP = planckianV(T)
            val den = uP * 2.0 - vP * 8.0 + 4.0
            val x = ((uP * 3.0) / den).toFloat()
            val y = ((vP * 2.0) / den).toFloat()
            val X = x / y
            val Z = (1f - x - y) / y
            // XYZ -> linear Rec.709 RGB, rows of the exact matrix with Y normalized to 1.
            val wr = 3.2406f * X + (-1.5372f) + (-0.4986f * Z)
            val wg = (-0.9689f) * X + 1.8758f + (0.0415f * Z)
            val wb = 0.0557f * X + (-0.2040f) + (1.0570f * Z)
            // CCM^-1 applied to the white RGB: the sensor raw values that map to neutral white.
            val rReq = 1.0f / ((inv[2] * wb) + (inv[1] * wg) + (inv[0] * wr))
            val bReq = 1.0f / ((inv[8] * wb) + (inv[7] * wg) + (inv[6] * wr))
            val gGM = sqrt(rReq * bReq) * fExp
            val minGain = minOf(rReq, gGM, bReq)
            val k = if (minGain < 1.0f) 1.0f / minGain else 1.0f
            gR = rReq * k
            gG = gGM * k
            gB = bReq * k
            val kelvinAchieved = cctEstimate(1.0f / gR, (1.0f / gG) * GREEN_BASELINE, 1.0f / gB, ccm)
            if (abs(kelvin - kelvinAchieved) < 0.1) break
            val correction = mired + ((targetMired - (1_000_000.0 / kelvinAchieved)) * 0.98)
            mired = correction.coerceIn(MIRED_MIN, MIRED_MAX)
        }
        if (abs(gR) > Float.MAX_VALUE || abs(gG) > Float.MAX_VALUE || abs(gB) > Float.MAX_VALUE ||
            gR.isNaN() || gG.isNaN() || gB.isNaN()) {
            return null
        }
        return RggbChannelVector(gR, gG, gG, gB)
    }

    /**
     * Same forward math, returned as green-normalized RGB multipliers for the viewfinder
     * shader trim (wbGains uniform) and for the live Kelvin/Tint HUD readout.
     */
    fun kelvinToNormalizedRgbGains(kelvin: Int, tint: Int, ccm: FloatArray): FloatArray {
        val v = kelvinToRggb(kelvin, tint, ccm)
        return if (v == null) {
            floatArrayOf(1f, 1f, 1f)
        } else {
            floatArrayOf(v.red / v.greenEven, 1f, v.blue / v.greenEven)
        }
    }

    /**
     * Relative Kelvin shader gains (QA Phase 4 law): the hardware ISP is locked to
     * CONTROL_AWB_MODE_DAYLIGHT (5600 K reference), so the GL trim only has to supply the
     * difference between the requested Kelvin/Tint and the 5600 K reference white point.
     * 5600 K + tint 0 produces EXACTLY (1, 1, 1). NaN/infinite denominators degrade to unity
     * and every channel is clamped to [SHADER_GAIN_MIN, SHADER_GAIN_MAX].
     */
    fun kelvinShaderGainsRelative(kelvin: Int, tint: Int, ccm: FloatArray): FloatArray {
        val safeKelvin = kelvin.coerceIn(2500, 10000)
        val safeTint = tint.coerceIn(-50, 50)
        if (safeKelvin == REFERENCE_KELVIN && safeTint == 0) return floatArrayOf(1f, 1f, 1f)
        val ref = kelvinToNormalizedRgbGains(REFERENCE_KELVIN, 0, ccm)
        val tgt = kelvinToNormalizedRgbGains(safeKelvin, safeTint, ccm)
        return FloatArray(3) { i ->
            val denom = ref[i]
            val ratio = if (denom.isNaN() || denom.isInfinite() || denom <= 1e-6f) 1f else tgt[i] / denom
            when {
                ratio.isNaN() || ratio.isInfinite() -> 1f
                else -> ratio.coerceIn(SHADER_GAIN_MIN, SHADER_GAIN_MAX)
            }
        }
    }

    /**
     * Gentle symmetric green-magenta shader trim for the AWB presets (QA Phase 4 law):
     * only applied when tint != 0 — gGain = exp(−tint/150), clamped to [0.75, 1.30].
     * Positive tint = magenta (green reduced), negative = green boost.
     */
    fun tintShaderGain(tint: Int): Float {
        val safe = tint.coerceIn(-50, 50).toFloat()
        val g = exp(-(safe / TINT_SHADER_DIVISOR))
        return if (g.isNaN() || g.isInfinite()) 1f else g.coerceIn(0.75f, 1.30f)
    }

    // -------------------------------------------------------------------------------------
    // REVERSE: (gains, ccm) -> (kelvin, tint)   (dt0.e + dt0.b)
    // -------------------------------------------------------------------------------------

    /**
     * CCT estimator (dt0.e): CCM applied to reciprocal gains, linear RGB -> XYZ (Rec.709,
     * 0.4124/0.3576/0.1805 | 0.2126/0.7152/0.0722 | 0.0193/0.1192/0.9505), CIE 1960 UCS
     * u = 4X/(X+15Y+3Z), v = 6Y/(X+15Y+3Z), then a golden-section minimization of the squared
     * uv distance to the Planckian locus over mired [40,1000] with 30 iterations.
     */
    fun cctEstimate(r: Float, g: Float, b: Float, ccm: FloatArray): Int {
        val rr = ccm[0] * r + ccm[1] * g + ccm[2] * b
        val gg = ccm[3] * r + ccm[4] * g + ccm[5] * b
        val bb = ccm[6] * r + ccm[7] * g + ccm[8] * b
        val X = 0.4124f * rr + 0.3576f * gg + 0.1805f * bb
        val Y = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
        val d = 3f * (0.0193f * rr + 0.1192f * gg + 0.9505f * bb) + 15f * Y + X
        if (d == 0f) return 5000 // DNSConstants.PROBE_THROTTLE_COUNT_INTERVAL sentinel
        val u = (4f * X).toDouble() / d.toDouble()
        val v = (6f * Y).toDouble() / d.toDouble()
        var lo = MIRED_MIN
        var hi = MIRED_MAX
        for (i in 0 until 30) {
            val c = hi - INVPHI * (hi - lo)
            val dd = lo + INVPHI * (hi - lo)
            val fc = locusDistance(u, v, c)
            val fd = locusDistance(u, v, dd)
            if (fc < fd) hi = dd else lo = c
        }
        return (1_000_000.0 / ((lo + hi) / 2.0)).toInt()
    }

    /**
     * Read-back (dt0.b): gains + CCM -> (kelvin, tint).
     * green input to CCT = (1 / mean(green gains)) * 0.72 sensor baseline;
     * tint = clamp(−200·ln(gG / sqrt(gR·gB)) − 130, −50, +50) on the RAW gains.
     */
    fun rggbToKelvinTint(gains: RggbChannelVector, ccm: FloatArray): Pair<Int, Int> {
        val red = 1.0f / gains.red
        val blue = 1.0f / gains.blue
        val greenMean = (gains.greenOdd + gains.greenEven) * 0.5f
        val green = if (gains.greenEven > 0f) (1.0f / greenMean) * GREEN_BASELINE else 0.0f
        val kelvin = cctEstimate(red, green, blue, ccm)
        var tint = 0
        if (gains.red > 0f && gains.blue > 0f && gains.greenEven > 0f) {
            val ratio = greenMean / sqrt(gains.blue * gains.red).toDouble()
            // QA Phase 4: the −130 device offset is gone — symmetric tint read-back only.
            tint = ((-ln(ratio).toFloat() * 200.0f).toInt()).coerceIn(-50, 50)
        }
        return Pair(kelvin, tint)
    }

    // -------------------------------------------------------------------------------------
    // 3x3 inverse (dt0.java:61-96) — standard adjugate/determinant, identity on det==0.
    // -------------------------------------------------------------------------------------

    fun inverse3x3(m: FloatArray): FloatArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val A = e * i - f * h
        val B = -(d * i - f * g)
        val C = d * h - e * g
        val det = a * A + b * B + c * C
        if (det == 0f || det.isNaN() || det.isInfinite()) return identityCcm()
        val invDet = 1.0f / det
        return floatArrayOf(
            A * invDet, B * invDet, C * invDet,
            -(b * i - c * h) * invDet, (a * i - c * g) * invDet, -(a * h - b * g) * invDet,
            (b * f - c * e) * invDet, -(a * f - c * d) * invDet, (a * e - b * d) * invDet
        )
    }
}
