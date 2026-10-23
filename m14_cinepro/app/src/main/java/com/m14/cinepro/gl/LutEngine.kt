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
     */
    fun generate(preset: Models.LutPreset): Lut3D? = when (preset) {
        Models.LutPreset.OFF -> null
        Models.LutPreset.IMPORTED -> importedPath?.let { loadImported(it) }
        Models.LutPreset.NEUTRAL_709 -> buildLut("Neutral Rec.709") { r, g, b, out ->
            out[0] = r; out[1] = g; out[2] = b
        }
        Models.LutPreset.TEAL_ORANGE -> buildLut("Cinema Teal & Orange", voxel = ::tealOrangeVoxel)
        Models.LutPreset.WARM_FADE -> buildLut("Warm Fade", voxel = ::warmFadeVoxel)
        Models.LutPreset.MONO_CONTRAST -> buildLut("Mono Contrast", voxel = ::monoContrastVoxel)
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

    /**
     * NEUTRAL_709 support / TEAL_ORANGE: per-channel film S-curve (smoothstep contrast,
     * pivot 0.435), shadows pushed teal (b +0.06, r -0.04), highlights warmed
     * (r +0.05, b -0.05), classic shoulder from the smoothstep contrast curve.
     */
    private fun tealOrangeVoxel(r: Float, g: Float, b: Float, out: FloatArray) {
        var cr = smoothContrast(r, 0.435f, 1.15f)
        var cg = smoothContrast(g, 0.435f, 1.15f)
        var cb = smoothContrast(b, 0.435f, 1.15f)
        val luma = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb
        val shadow = 1f - smoothstep01(luma / 0.5f)
        val high = smoothstep01((luma - 0.5f) / 0.5f)
        cr += -0.04f * shadow + 0.05f * high
        cb += 0.06f * shadow - 0.05f * high
        out[0] = cr; out[1] = cg; out[2] = cb
    }

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
        out[0] = highlightRollOff(cr)
        out[1] = highlightRollOff(cg)
        out[2] = highlightRollOff(cb)
    }

    /**
     * MONO_CONTRAST: Rec.709 luma then a strong smoothstep S-curve
     * (contrast ~1.25 around 0.45).
     */
    private fun monoContrastVoxel(r: Float, g: Float, b: Float, out: FloatArray) {
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val c = smoothContrast(luma, 0.45f, 1.25f)
        out[0] = c; out[1] = c; out[2] = c
    }

    /** Standard smoothstep on a t already normalized to [0,1] (clamped). */
    private fun smoothstep01(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * Smoothstep contrast curve centered at [center]: 0 -> 0, center -> center, 1 -> 1,
     * mid-slope multiplied by [strength] (values > 1 crush/clip the tails after clamp,
     * which is the intended filmic shoulder/toe behavior).
     */
    private fun smoothContrast(x: Float, center: Float, strength: Float): Float =
        if (x < center) {
            center - smoothstep01((center - x) / center) * center * strength
        } else {
            center + smoothstep01((x - center) / (1f - center)) * (1f - center) * strength
        }

    /** Gentle highlight roll-off above the 0.8 knee; identity below the knee, 1 -> 1. */
    private fun highlightRollOff(x: Float): Float {
        val knee = 0.8f
        return if (x <= knee) x else knee + (1f - knee) * smoothstep01((x - knee) / (1f - knee))
    }

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
