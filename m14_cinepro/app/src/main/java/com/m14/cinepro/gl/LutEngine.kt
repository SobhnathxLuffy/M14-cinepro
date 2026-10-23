package com.m14.cinepro.gl

import android.util.Log
import com.m14.cinepro.core.Models
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * M14 CinePro — 3D LUT engine (stage4_shaders.md §7 + §10.3).
 *
 * Responsibilities:
 *  - Generate the four built-in 33^3 display-referred LUTs (NEUTRAL_709 / TEAL_ORANGE /
 *    WARM_FADE / MONO_CONTRAST) as half-float RGBA voxels.
 *  - Parse user-imported .cube files (full grammar: TITLE, LUT_3D_SIZE, LUT_1D_SIZE (ignored
 *    data skipped), DOMAIN_MIN/MAX inverse re-gridding, comments) into the same representation.
 *  - Bake the 33^3 luminance 3D LUT (RGB -> CIE L* normalized) consumed by
 *    luminance_function.glsl (stage4 §1.9) for zebras + fast false color.
 *  - IEEE 754 binary16 conversion with the full bit algorithm (denormals, ties-to-even,
 *    overflow clamps to the inf range).
 *
 * Voxel layout law: index i = (b*size^2 + g*size + r)*4 — red fastest, matching the .cube
 * file convention and the RGBA16F GL_TEXTURE_3D upload of stage4 §7.4.
 *
 * All LUTs are display-referred Rec.709/sRGB-ish [0,1] transforms with alpha = 1.0
 * (the shader `lut3D()` returns the fetched texel verbatim, including alpha).
 */
class LutEngine {

    companion object {
        private const val TAG = "M14GL"

        /** Built-in grid resolution (stage4 law: 33^3 everywhere). */
        const val LUT_SIZE = 33

        // CIE L* constants (stage4 §1.5 / §1.9).
        private const val LSTAR_C1 = 0.008856451679036f   // (6/29)^3
        private const val LSTAR_C2 = 903.2962962962963f   // (29/3)^3

        // Rec.709 transfer function constants (stage4 §1.5).
        private const val REC709_LINEAR_THRESHOLD = 0.081f
        private const val REC709_LINEAR_SLOPE = 4.5f
        private const val REC709_OFFSET = 0.099f
        private const val REC709_SCALE_FACTOR = 1.099f
        private const val REC709_GAMMA_RECIPROCAL = 0.45f
    }

    /** A ready-to-upload 3D LUT: size^3 voxels x 4 half floats (RGBA), red fastest. */
    class Lut3D(val size: Int, val rgbaHalf: ShortArray, val title: String)

    /** Path of the user-imported .cube file, set by [GlViewfinder.applyLut]. */
    @Volatile
    var importedPath: String? = null

    // -----------------------------------------------------------------------------------------
    // Generation
    // -----------------------------------------------------------------------------------------

    /**
     * Resolve a preset to a LUT. OFF -> null (identity fallback, lut3DVisible = 0).
     * IMPORTED -> parse [importedPath]; null when absent/broken (caller falls back).
     *
     * V2 film-emulation family (CP6): every generator is a real tone-shaping math build —
     * monotone knot curves, YIQ axis work with skin-vector protection, per-channel print
     * crossovers — not single-key color tints.
     */
    fun generate(preset: Models.LutPreset): Lut3D? = when (preset) {
        Models.LutPreset.OFF -> null
        Models.LutPreset.IMPORTED -> importedPath?.let { loadImported(it) }
        Models.LutPreset.NEUTRAL_709 -> buildLut("Rec.709 Soft Shoulder", voxel = ::neutral709Voxel)
        Models.LutPreset.TEAL_ORANGE -> buildLut("Teal & Orange YIQ", voxel = ::tealOrangeVoxel)
        Models.LutPreset.KODAK_2383 -> buildLut("Kodak 2383 Print", voxel = ::kodak2383Voxel)
        Models.LutPreset.ETERNA -> buildLut("Fujifilm Eterna", voxel = ::eternaVoxel)
        Models.LutPreset.WARM_FADE -> buildLut("Warm Fade", voxel = ::warmFadeVoxel)
        Models.LutPreset.MONO_CONTRAST -> buildLut("Panchromatic B&W", voxel = ::monoVoxel)
    }

    /** Generic 33^3 voxel builder; voxel() writes sRGB-ish display-referred RGB into out[0..2]. */
    private fun buildLut(
        title: String,
        size: Int = LUT_SIZE,
        voxel: (Float, Float, Float, FloatArray) -> Unit
    ): Lut3D {
        val half = ShortArray(size * size * size * 4)
        val out = FloatArray(3)
        val n = (size - 1).toFloat()
        var i = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            voxel(r / n, g / n, b / n, out)
            half[i++] = toHalf(out[0].coerceIn(0f, 1f))
            half[i++] = toHalf(out[1].coerceIn(0f, 1f))
            half[i++] = toHalf(out[2].coerceIn(0f, 1f))
            half[i++] = toHalf(1f)
        }
        return Lut3D(size, half, title)
    }

    // -----------------------------------------------------------------------------------------
    // Curve / color math shared by the generators
    // -----------------------------------------------------------------------------------------

    /** Standard smoothstep on a t already normalized to [0,1] (clamped). */
    private fun smoothstep01(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * Monotone filmic tone curve through [x0,y0, x1,y1, …] knots: inside each segment the
     * smoothstep blend gives zero slope at every knot (soft toe + soft shoulder), the curve is
     * strictly monotone, 0->knot0 and knotEnd->1 continue flat. This is the classic way film
     * print curves are authored as control points.
     */
    private fun toneCurve(x: Float, knots: FloatArray): Float {
        if (x <= knots[0]) return knots[1]
        val segments = knots.size / 2 - 1
        for (s in 0 until segments) {
            val x0 = knots[s * 2]
            val y0 = knots[s * 2 + 1]
            val x1 = knots[s * 2 + 2]
            val y1 = knots[s * 2 + 3]
            if (x <= x1) {
                val t = ((x - x0) / (x1 - x0)).coerceIn(0f, 1f)
                return y0 + (y1 - y0) * (t * t * (3f - 2f * t))
            }
        }
        return knots[knots.size - 1]
    }

    /** Smoothstep contrast curve centered at [center] (0/center/1 fixed, mid slope x[strength]). */
    private fun smoothContrast(x: Float, center: Float, strength: Float): Float =
        if (x < center) {
            center - smoothstep01((center - x) / center) * center * strength
        } else {
            center + smoothstep01((x - center) / (1f - center)) * (1f - center) * strength
        }

    /** Gentle highlight roll-off above the [knee]; identity below the knee, 1 -> 1. */
    private fun highlightRollOff(x: Float, knee: Float): Float =
        if (x <= knee) x else knee + (1f - knee) * smoothstep01((x - knee) / (1f - knee))

    /** Rec.709 luma of display-referred RGB. */
    private fun luma709(r: Float, g: Float, b: Float): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b

    /** RGB -> YIQ (NTSC 1953 weights; the axis Teal & Orange work is done in). */
    private fun rgbToYiq(r: Float, g: Float, b: Float, out: FloatArray) {
        out[0] = 0.299f * r + 0.587f * g + 0.114f * b
        out[1] = 0.596f * r - 0.274f * g - 0.322f * b
        out[2] = 0.211f * r - 0.523f * g + 0.312f * b
    }

    /** YIQ -> RGB (inverse of [rgbToYiq]). */
    private fun yiqToRgb(y: Float, i: Float, q: Float, out: FloatArray) {
        out[0] = y + 0.956f * i + 0.621f * q
        out[1] = y - 0.272f * i - 0.647f * q
        out[2] = y - 1.106f * i + 1.703f * q
    }

    /**
     * Skin-tone protection weight: gaussian on the YIQ chroma plane around the caucasian skin
     * vector (I ≈ 0.32, Q ≈ 0.10); 1.0 = dead-on skin, -> 0 away from it. Film grades protect
     * this vector so the teal/warm pushes never turn faces strange.
     */
    private fun skinWeight(i: Float, q: Float): Float {
        val di = (i - 0.32f) / 0.10f
        val dq = (q - 0.10f) / 0.08f
        return Math.exp(-(di * di + dq * dq) * 0.5).toFloat()
    }

    /**
     * Luma-weighted saturation: out = y + s(x)·(c − y). s rises from 0 at pure black/white to
     * [peak] in the mids, so saturation work never clips the endpoints (hue-preserving).
     */
    private fun saturateLumaProtected(c: FloatArray, amount: Float) {
        val y = luma709(c[0], c[1], c[2])
        val edge = 1f - Math.abs(2f * y - 1f).coerceAtMost(1f)
        val s = 1f + amount * (0.35f + 0.65f * edge)
        c[0] = y + (c[0] - y) * s
        c[1] = y + (c[1] - y) * s
        c[2] = y + (c[2] - y) * s
    }

    // -----------------------------------------------------------------------------------------
    // The V2 film-emulation generators
    // -----------------------------------------------------------------------------------------

    /**
     * NEUTRAL_709 — "Rec.709 soft shoulder" (CP6 law): the clean graded baseline. Monotone
     * print-style tone curve (soft toe at the blacks, gentle mid contrast ~1.12, cosine-soft
     * shoulder into the whites) applied per channel — camera Rec.709 stops looking like raw
     * video and starts looking like a finished negative.
     */
    private fun neutral709Voxel(r: Float, g: Float, b: Float, out: FloatArray) {
        out[0] = toneCurve(r, NEUTRAL_KNOTS)
        out[1] = toneCurve(g, NEUTRAL_KNOTS)
        out[2] = toneCurve(b, NEUTRAL_KNOTS)
    }

    private val NEUTRAL_KNOTS = floatArrayOf(
        0.00f, 0.000f,
        0.10f, 0.062f,
        0.35f, 0.305f,
        0.72f, 0.692f,
        0.92f, 0.908f,
        1.00f, 1.000f
    )

    /**
     * TEAL_ORANGE — true YIQ skin-protected complementary grade (CP6 law): luma contrast in Y,
     * then −I (teal/cyan) pushed into the shadows and +I (warm/orange) into the highlights,
     * both scaled by (1 − skinWeight) so faces hold their hue; small +Q in shadows trims the
     * teal away from pure green. Back through YIQ -> RGB.
     */
    private fun tealOrangeVoxel(r: Float, g: Float, b: Float, out: FloatArray) {
        val yiq = FloatArray(3)
        rgbToYiq(r, g, b, yiq)
        val y = smoothContrast(yiq[0], 0.435f, 1.14f)

        val shadow = 1f - smoothstep01(y / 0.45f)
        val high = smoothstep01((y - 0.55f) / 0.45f)
        val skin = skinWeight(yiq[1], yiq[2])
        val protect = 1f - 0.85f * skin

        val i = yiq[1] - 0.075f * shadow * protect + 0.055f * high * protect
        val q = yiq[2] + 0.022f * shadow * protect - 0.012f * high

        yiqToRgb(y, i, q, out)
        out[0] = highlightRollOff(out[0], 0.85f)
        out[1] = highlightRollOff(out[1], 0.85f)
        out[2] = highlightRollOff(out[2], 0.85f)
    }

    /**
     * KODAK_2383 — print-film emulation (CP6 law): deep print-density toe with a small black
     * floor, strong mid S-contrast, warm highlight roll-off (blue shoulders earliest), green
     * mids held slightly down, then a luma-protected saturation lift — the classic positive
     * print character.
     */
    private fun kodak2383Voxel(r: Float, g: Float, b: Float, out: FloatArray) {
        // Per-channel density S-curves: red strongest mids, blue shoulders first (warm highs).
        var cr = smoothContrast(r, 0.42f, 1.22f)
        var cg = smoothContrast(g, 0.42f, 1.18f) - 0.012f * smoothstep01((g - 0.45f) / 0.25f)
        var cb = smoothContrast(b, 0.42f, 1.22f)
        // Print black floor (2383 dense-but-not-zero blacks) + per-channel shoulder crossovers.
        cr = 0.024f + 0.976f * highlightRollOff(cr, 0.80f)
        cg = 0.024f + 0.976f * highlightRollOff(cg, 0.76f)
        cb = 0.024f + 0.976f * highlightRollOff(cb, 0.70f)
        out[0] = cr
        out[1] = cg
        out[2] = cb
        saturateLumaProtected(out, 0.16f)
    }

    /**
     * ETERNA — low-contrast, low-saturation negative film (CP6 law): flat monotone curve with a
     * lifted toe and early soft shoulder, saturation pulled back (mids only, endpoints
     * protected), cool-green shadow bias and a whisper of warmth in the highlights.
     */
    private fun eternaVoxel(r: Float, g: Float, b: Float, out: FloatArray) {
        out[0] = toneCurve(r, ETERNA_KNOTS)
        out[1] = toneCurve(g, ETERNA_KNOTS)
        out[2] = toneCurve(b, ETERNA_KNOTS)
        val y = luma709(out[0], out[1], out[2])
        val shadow = 1f - smoothstep01(y / 0.5f)
        val high = smoothstep01((y - 0.5f) / 0.5f)
        out[0] += 0.004f * high - 0.010f * shadow
        out[1] += 0.002f * high + 0.012f * shadow
        out[2] += 0.000f * high + 0.010f * shadow
        saturateLumaProtected(out, -0.22f)
    }

    private val ETERNA_KNOTS = floatArrayOf(
        0.00f, 0.020f,
        0.25f, 0.232f,
        0.75f, 0.738f,
        1.00f, 0.968f
    )

    /**
     * WARM_FADE: lifted blacks (+0.05 at 0, white preserved), warm gain (r *1.04, b *0.94),
     * gentle highlight roll-off above a 0.8 knee (smoothstep shoulder, 1 -> 1).
     */
    private fun warmFadeVoxel(r: Float, g: Float, b: Float, out: FloatArray) {
        var cr = 0.05f + 0.95f * r
        var cg = 0.05f + 0.95f * g
        var cb = 0.05f + 0.95f * b
        cr *= 1.04f
        cb *= 0.94f
        out[0] = highlightRollOff(cr, 0.8f)
        out[1] = highlightRollOff(cg, 0.8f)
        out[2] = highlightRollOff(cb, 0.8f)
    }

    /**
     * MONO_CONTRAST — panchromatic B&W (CP6 law): pan-film spectral response weights
     * (red-boosted 0.32 / green 0.50 / blue-suppressed 0.18 — NOT the 709 luma dot, which is
     * what made V1 mono look flat), then a dense print tone curve with deep toe and soft
     * shoulder.
     */
    private fun monoVoxel(r: Float, g: Float, b: Float, out: FloatArray) {
        val pan = 0.32f * r + 0.50f * g + 0.18f * b
        val c = toneCurve(pan, MONO_KNOTS)
        out[0] = c
        out[1] = c
        out[2] = c
    }

    private val MONO_KNOTS = floatArrayOf(
        0.00f, 0.000f,
        0.12f, 0.055f,
        0.46f, 0.415f,
        0.80f, 0.840f,
        1.00f, 1.000f
    )

    // -----------------------------------------------------------------------------------------
    // .cube import (stage4 §7.3 user-import path)
    // -----------------------------------------------------------------------------------------

    /**
     * Full .cube grammar: TITLE, LUT_3D_SIZE (required), LUT_1D_SIZE (1D data lines are
     * skipped — they precede the 3D table per the Adobe spec), DOMAIN_MIN/MAX (inverse
     * domain scaling is baked in via trilinear re-gridding so the uploaded texture keeps
     * the [0,1]^3 domain the shader samples), '#' comments, blank lines, "r g b" floats.
     * Returns null (and logs) on any malformed/incomplete input — caller shows import-failed.
     */
    fun parseCube(inputStream: InputStream): Lut3D? {
        try {
            inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    return parseCubeReader(reader)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "M14GL .cube parse failed: ${t.message}")
            return null
        }
    }

    private fun parseCubeReader(reader: BufferedReader): Lut3D? {
        var title = "Imported LUT"
        var size = -1
        var size1d = -1
        var dmin = floatArrayOf(0f, 0f, 0f)
        var dmax = floatArrayOf(1f, 1f, 1f)
        var table = FloatArray(0)
        var fill = 0
        var skipped1d = 0

        var line = reader.readLine()
        while (line != null) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) {
                line = reader.readLine()
                continue
            }
            when {
                t.startsWith("TITLE") -> title = parseTitle(t)

                t.startsWith("LUT_3D_SIZE") -> {
                    val parsed = firstInt(t.substring("LUT_3D_SIZE".length))
                    size = parsed
                    table = if (size >= 2) FloatArray(size * size * size * 3) else FloatArray(0)
                    fill = 0
                }

                t.startsWith("LUT_1D_SIZE") -> size1d = firstInt(t.substring("LUT_1D_SIZE".length))

                t.startsWith("DOMAIN_MIN") -> parse3(t.substring("DOMAIN_MIN".length))?.let { dmin = it }
                t.startsWith("DOMAIN_MAX") -> parse3(t.substring("DOMAIN_MAX".length))?.let { dmax = it }

                else -> {
                    val v = parse3(t)
                    if (v != null) {
                        if (size1d > 0 && skipped1d < size1d) {
                            // Adobe spec: 1D table data lines come before the 3D table; skip them.
                            skipped1d++
                        } else if (size >= 2 && fill < size * size * size) {
                            val o = fill * 3
                            table[o] = v[0]; table[o + 1] = v[1]; table[o + 2] = v[2]
                            fill++
                        }
                        // Extra data lines beyond the declared grid are ignored (grammar-tolerant).
                    }
                }
            }
            line = reader.readLine()
        }

        if (size < 2) {
            Log.w(TAG, "M14GL .cube parse: LUT_3D_SIZE missing/invalid")
            return null
        }
        val need = size * size * size
        if (fill < need) {
            Log.w(TAG, "M14GL .cube parse: incomplete 3D table (size=$size need=$need got=$fill)")
            return null
        }

        val src = if (domainIsIdentity(dmin, dmax)) table else regridToUnitDomain(table, size, dmin, dmax)

        val half = ShortArray(need * 4)
        var i = 0
        for (v in 0 until need) {
            half[i++] = toHalf(src[v * 3].coerceIn(0f, 1f))
            half[i++] = toHalf(src[v * 3 + 1].coerceIn(0f, 1f))
            half[i++] = toHalf(src[v * 3 + 2].coerceIn(0f, 1f))
            half[i++] = toHalf(1f)
        }
        return Lut3D(size, half, title)
    }

    private fun parseTitle(t: String): String {
        val rest = t.substring("TITLE".length).trim()
        return if (rest.isEmpty()) "Imported LUT" else rest.removeSurrounding("\"")
    }

    private fun firstInt(rest: String): Int =
        Regex("\\d+").find(rest)?.value?.toIntOrNull() ?: -1

    private fun parse3(rest: String): FloatArray? {
        val parts = rest.trim().split(Regex("\\s+"))
        if (parts.size < 3) return null
        val a = parts[0].toFloatOrNull() ?: return null
        val b = parts[1].toFloatOrNull() ?: return null
        val c = parts[2].toFloatOrNull() ?: return null
        return floatArrayOf(a, b, c)
    }

    private fun domainIsIdentity(dmin: FloatArray, dmax: FloatArray): Boolean {
        for (axis in 0 until 3) {
            if (Math.abs(dmin[axis]) > 1e-9f) return false
            if (Math.abs(dmax[axis] - 1f) > 1e-9f) return false
        }
        return true
    }

    /**
     * DOMAIN_MIN/MAX law: input coordinate v maps to domain position dmin + v*(dmax-dmin),
     * so the stored table G[p] = f(dmin + (dmax-dmin)*p). The shader samples with the fixed
     * half-voxel correction on [0,1], therefore bake T[p] = f(p) = G[domainInv(p)] here via
     * trilinear sampling of the file grid at the inverse-domain coordinates.
     */
    private fun regridToUnitDomain(table: FloatArray, size: Int, dmin: FloatArray, dmax: FloatArray): FloatArray {
        val out = FloatArray(size * size * size * 3)
        val inv = FloatArray(3)
        for (axis in 0 until 3) {
            val span = dmax[axis] - dmin[axis]
            inv[axis] = if (Math.abs(span) < 1e-9f) 0f else 1f / span
        }
        val n = (size - 1).toFloat()
        var o = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val x = (r / n - dmin[0]) * inv[0]
            val y = (g / n - dmin[1]) * inv[1]
            val z = (b / n - dmin[2]) * inv[2]
            sampleTrilinear(table, size, x, y, z, out, o)
            o += 3
        }
        return out
    }

    /** Trilinear sample of a size^3 x 3 float table (voxel order: r fastest, then g, then b). */
    private fun sampleTrilinear(table: FloatArray, size: Int, x: Float, y: Float, z: Float, out: FloatArray, outOffset: Int) {
        val max = size - 1
        val fx = x.coerceIn(0f, 1f) * max
        val fy = y.coerceIn(0f, 1f) * max
        val fz = z.coerceIn(0f, 1f) * max
        val x0 = fx.toInt().coerceIn(0, max - 1)
        val y0 = fy.toInt().coerceIn(0, max - 1)
        val z0 = fz.toInt().coerceIn(0, max - 1)
        val tx = fx - x0
        val ty = fy - y0
        val tz = fz - z0
        val x1 = x0 + 1
        val y1 = y0 + 1
        val z1 = z0 + 1
        val s2 = size * size
        var c = outOffset
        for (ch in 0 until 3) {
            val c000 = table[(z0 * s2 + y0 * size + x0) * 3 + ch]
            val c100 = table[(z0 * s2 + y0 * size + x1) * 3 + ch]
            val c010 = table[(z0 * s2 + y1 * size + x0) * 3 + ch]
            val c110 = table[(z0 * s2 + y1 * size + x1) * 3 + ch]
            val c001 = table[(z1 * s2 + y0 * size + x0) * 3 + ch]
            val c101 = table[(z1 * s2 + y0 * size + x1) * 3 + ch]
            val c011 = table[(z1 * s2 + y1 * size + x0) * 3 + ch]
            val c111 = table[(z1 * s2 + y1 * size + x1) * 3 + ch]
            val c00 = c000 + (c100 - c000) * tx
            val c10 = c010 + (c110 - c010) * tx
            val c01 = c001 + (c101 - c001) * tx
            val c11 = c011 + (c111 - c011) * tx
            val c0 = c00 + (c10 - c00) * ty
            val c1 = c01 + (c11 - c01) * ty
            out[c++] = c0 + (c1 - c0) * tz
        }
    }

    /** Load a user .cube from a filesystem path (FileInputStream -> parseCube). */
    fun loadImported(path: String): Lut3D? = try {
        FileInputStream(File(path)).use { parseCube(it) }
    } catch (t: Throwable) {
        Log.w(TAG, "M14GL LUT import failed for $path: ${t.message}")
        null
    }

    // -----------------------------------------------------------------------------------------
    // Luminance LUT bake (stage4 §1.9)
    // -----------------------------------------------------------------------------------------

    /**
     * 33^3 bake of RGB -> normalized CIE L*: per channel rec709DecodeGamma, Y = 709 dot,
     * L* = Y<=C1 ? Y*C2 : cbrt(Y)*116-16, normalized /100. All four half-float channels
     * carry L* (the shader reads .r).
     */
    fun bakeLuminanceLut(): Lut3D {
        val size = LUT_SIZE
        val half = ShortArray(size * size * size * 4)
        val n = (size - 1).toFloat()
        var i = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val lr = rec709DecodeGamma(r / n)
            val lg = rec709DecodeGamma(g / n)
            val lb = rec709DecodeGamma(b / n)
            val y = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
            val l = y2Lstar(y)
            val h = toHalf(l)
            half[i++] = h
            half[i++] = h
            half[i++] = h
            half[i++] = h
        }
        return Lut3D(size, half, "Luminance L* Rec.709")
    }

    /** Rec.709 OETF inverse (stage4 §1.5 constants). */
    private fun rec709DecodeGamma(v: Float): Float =
        if (v <= REC709_LINEAR_THRESHOLD) {
            v / REC709_LINEAR_SLOPE
        } else {
            Math.pow(((v + REC709_OFFSET) / REC709_SCALE_FACTOR).toDouble(), 1.0 / REC709_GAMMA_RECIPROCAL).toFloat()
        }

    /** CIE Y -> L* normalized to [0,1] (stage4 §1.5). */
    private fun y2Lstar(y: Float): Float {
        val raw = if (y <= LSTAR_C1) y * LSTAR_C2 else Math.cbrt(y.toDouble()).toFloat() * 116.0f - 16.0f
        return raw / 100.0f
    }

    // -----------------------------------------------------------------------------------------
    // IEEE 754 binary16 conversion (full bit algorithm)
    // -----------------------------------------------------------------------------------------

    /**
     * Float -> half (IEEE 754 binary16), round-to-nearest-even, denormals supported,
     * overflow clamps to the inf range, NaN -> canonical 0x7E00 (sign preserved), -0 kept.
     */
    fun toHalf(v: Float): Short {
        val bits = java.lang.Float.floatToIntBits(v)
        val sign = (bits ushr 16) and 0x8000
        if (v.isNaN()) return (sign or 0x7E00).toShort()
        if (v.isInfinite()) return (sign or 0x7C00).toShort()
        if ((bits and 0x7FFFFFFF) == 0) return (bits ushr 16).toShort()

        val exp = ((bits ushr 23) and 0xFF) - 127 + 15
        var mant = bits and 0x7FFFFF
        var h: Int
        if (exp >= 0x1F) {
            // Overflow: clamp to the inf range.
            h = 0x7C00
        } else if (exp <= 0) {
            if (exp < -10) {
                h = 0
            } else {
                // Half subnormal: shift the implicit-bit-extended mantissa, ties-to-even.
                mant = mant or 0x800000
                val shift = 14 - exp
                h = mant ushr shift
                val roundBit = 1 shl (shift - 1)
                if ((mant and roundBit) != 0 && ((mant and (roundBit - 1)) != 0 || (h and 1) != 0)) h += 1
            }
        } else {
            val halfMant = mant ushr 13
            h = (exp shl 10) or halfMant
            if ((mant and 0x1000) != 0 && ((mant and 0x0FFF) != 0 || (halfMant and 1) != 0)) h += 1
        }
        return (sign or h).toShort()
    }

    // -----------------------------------------------------------------------------------------
    // Half-voxel-center sampling law (stage4 §1.10 / §7.4 / §10.3)
    // -----------------------------------------------------------------------------------------

    /** lut3DScale / luminanceScale = 1 - 1/N. */
    fun halfVoxelScale(size: Int): Float = 1f - 1f / size

    /** lut3DOffset / luminanceOffset = (1/N)/2. */
    fun halfVoxelOffset(size: Int): Float = (1f / size) / 2f
}
