package com.m14.cinepro.core

import android.content.Context
import android.content.SharedPreferences

/**
 * M14 CinePro — central domain model + persistent settings store.
 *
 * This file is the shared contract between the camera engine, the GL viewfinder, the recording
 * pipeline and the HUD. All values follow the empirical law of:
 *   docs/M14_VERIFIED_TRUTH.md, docs/M14_SYSTEM_STRESS_REPORT.md,
 *   docs/stage3_camera2.md, docs/stage4_shaders.md, docs/stage5_media.md
 *
 * Thread-safety: every setter writes through to SharedPreferences on a background executor and
 * mutates the in-memory field synchronously. UI + engine threads read the in-memory field.
 */
object Models {

    // -----------------------------------------------------------------------------------------
    // Enums
    // -----------------------------------------------------------------------------------------

    /** Recording formats verified on SM-M146B (M14_VERIFIED_TRUTH.md §4). */
    enum class ResPreset(val width: Int, val height: Int, val fps: Int, val label: String, val highSpeed: Boolean) {
        UHD_4K_24(3840, 2160, 24, "4K 24", false),
        UHD_4K_30(3840, 2160, 30, "4K 30", false),
        QHD_25K_24(2560, 1440, 24, "2.5K 24", false),
        QHD_25K_30(2560, 1440, 30, "2.5K 30", false),
        FHD_1080_24(1920, 1080, 24, "1080p 24", false),
        FHD_1080_30(1920, 1080, 30, "1080p 30", false),
        HD_720_60_HS(1280, 720, 60, "720p 60 Slow-Mo", true)
    }

    /** Bitrate selector (stage5_media.md §2.4; Exynos MFC max verified 81 Mbps at 4K). */
    enum class BitratePreset(val bps: Int, val label: String) {
        M80(80_000_000, "80M"),
        M48(48_000_000, "48M"),
        M36(36_000_000, "36M"),
        M24(24_000_000, "24M")
    }

    enum class ShutterMode { ANGLE, SPEED }

    enum class AntiBandingMode(val camera2Value: Int) {
        OFF(android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF),
        FIFTY_HZ(android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ),
        SIXTY_HZ(android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ),
        AUTO(android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
    }

    /** Focus peaking render mode (stage4_shaders.md §2). */
    enum class PeakingMode { OFF, COLORED_LINES, PEAK }

    enum class PeakingColor(val rgba: FloatArray) {
        RED(floatArrayOf(1f, 0f, 0f, 1f)),
        GREEN(floatArrayOf(0f, 1f, 0.2f, 1f)),
        BLUE(floatArrayOf(0.2f, 0.6f, 1f, 1f)),
        YELLOW(floatArrayOf(1f, 0.9f, 0.1f, 1f)),
        WHITE(floatArrayOf(1f, 1f, 1f, 1f));
        companion object { fun fromName(n: String?): PeakingColor = entries.firstOrNull { it.name == n } ?: RED }
    }

    /** Zebra display mode (stage4_shaders.md §4; mid-grey bands are 10-bit Y code values /1024). */
    enum class ZebraMode { OFF, Z95, Z90, MID_GREY, MID_GREY_PLUS1 }

    /**
     * Built-in 33^3 film-emulation LUTs + user .cube import (stage4_shaders.md §7).
     * V2 generator family: Rec.709 soft-shoulder, YIQ skin-protected Teal & Orange,
     * Kodak 2383 print curve, Fujifilm Eterna, Panchromatic B&W.
     */
    enum class LutPreset { OFF, NEUTRAL_709, TEAL_ORANGE, KODAK_2383, ETERNA, WARM_FADE, MONO_CONTRAST, IMPORTED }

    enum class WbPreset { AUTO, DAYLIGHT, CLOUDY_DAYLIGHT, INCANDESCENT, FLUORESCENT, KELVIN }

    /** Aspect frame guides. */
    enum class GuidePreset(val ratio: Float, val label: String) {
        OFF(0f, "Off"),
        R_2_39(2.39f, "2.39:1"),
        R_2_00(2.00f, "2.00:1"),
        R_1_85(1.85f, "1.85:1"),
        R_4_3(4f / 3f, "4:3"),
        R_9_16(9f / 16f, "9:16")
    }

    enum class GridOverlay { NONE, THIRDS, CROSSHAIR, HORIZON }

    /** Anamorphic de-squeeze factors (stage4_shaders.md §9). */
    enum class DesqueezePreset(val factor: Float, val label: String) {
        X100(1.0f, "1.0x"),
        X133(1.33f, "1.33x"),
        X155(1.55f, "1.55x"),
        X200(2.0f, "2.0x")
    }

    /** VU/PPM meter reference (stage5_media.md §5.6 — AudioMetering enum). */
    enum class AudioMeteringMode(val refCentiDb: Int, val label: String) {
        VU_18DBFS(-1800, "VU -18"),
        VU_20DBFS(-2000, "VU -20"),
        PPM_18DBFS(-1800, "PPM -18"),
        PPM_20DBFS(-2000, "PPM -20")
    }

    // -----------------------------------------------------------------------------------------
    // Numeric law (verified constants — do not tune)
    // -------------------------------------------------------------------------

    /** 110 µs headroom snap: exposure must end 110 µs before the frame duration ends (stage3 §2.1). */
    const val EXPOSURE_HEADROOM_NS = 110_000L
    const val EXPOSURE_MIN_NS = 30_000L
    const val EXPOSURE_MAX_NS = 100_000_000L
    const val ISO_MIN = 100
    const val ISO_MAX = 3200
    const val KELVIN_MIN = 2500
    const val KELVIN_MAX = 10000
    const val TINT_MIN = -50
    const val TINT_MAX = 50
    const val EV_MIN = -2.0f
    const val EV_MAX = 2.0f

    /** Zebra threshold display-profile scale (stage4 §10.3: x0.93 for Rec.709 display). */
    const val ZEBRA_THRESHOLD_SCALE_709 = 0.93f
    /** Peaking strength UI -> shader scale (stage4 §10.3: peakingThreshold = UI * 0.04). */
    const val PEAKING_UI_SCALE = 0.04f
    const val PEAKING_DEFAULT_UI = 0.85f
    const val ZEBRA_WIDTH_PX = 8.0f
    const val ZEBRA_DEFAULT_UI_THRESHOLD = 0.75f

    /** Viewfinder stream is 1920x1080 in regular sessions, 1280x720 in the HFR session. */
    const val VIEWFINDER_WIDTH = 1920
    const val VIEWFINDER_HEIGHT = 1080

    /** The viewfinder picture is always exact 16:9 (CP1 law). */
    const val PICTURE_ASPECT = 16f / 9f

    /** Digital zoom law (QA Phase 6): Camera 0 CONTROL_ZOOM_RATIO range 1.0x..8.0x, 26 mm equiv. */
    const val ZOOM_MIN = 1.0f
    const val ZOOM_MAX = 8.0f
    const val ZOOM_DEFAULT = 1.0f
    const val FOCAL_EQUIV_MM = 26
    /** Servo rack-zoom sweep duration (QA Phase 6). */
    const val RACK_ZOOM_MS = 1500L

    /**
     * CP1 16:9 viewport-lock law: the viewfinder picture is the largest centered 16:9 rect
     * inside a [screenW] x [screenH] window (e.g. 1920x1080 centered inside 2343x1080),
     * everything else is cleared black (pillar/letterbox). Shared by the GL renderer (GL-space
     * viewport, y origin bottom-left but vertically symmetric) and the HUD overlays
     * (view-space, y origin top-left).
     * @return floatArrayOf(left, top, width, height)
     */
    fun pictureRect(screenW: Int, screenH: Int): FloatArray = pictureRect(screenW, screenH, 1f)

    /**
     * Anamorphic de-squeeze variant (QA Phase 2): with an active factor the 16:9 picture is
     * stretched horizontally by [desqueezeFactor] (stage4 §9) — the picture rect widens around
     * its vertical center, clamped to the window. The height never changes (de-squeeze is a
     * horizontal-only transform).
     */
    fun pictureRect(screenW: Int, screenH: Int, desqueezeFactor: Float): FloatArray {
        val w = screenW.coerceAtLeast(1)
        val h = screenH.coerceAtLeast(1)
        var ph = h.toFloat()
        var pw = ph * PICTURE_ASPECT
        if (pw > w) {
            pw = w.toFloat()
            ph = pw / PICTURE_ASPECT
        }
        val top = (h - ph) * 0.5f
        val f = if (desqueezeFactor.isNaN() || desqueezeFactor.isInfinite()) 1f
        else desqueezeFactor.coerceAtLeast(1f)
        if (f <= 1f) return floatArrayOf((w - pw) * 0.5f, top, pw, ph)
        var sw = pw * f
        if (sw > w) sw = w.toFloat()
        return floatArrayOf((w - sw) * 0.5f, top, sw, ph)
    }

    /**
     * Standard 1/3-stop cinema ISO ladder (QA Phase 6): 100..3200.
     * Sliders index this array; the value is never interpolated.
     */
    fun isoStops(): FloatArray = floatArrayOf(
        100f, 125f, 160f, 200f, 250f, 320f, 400f, 500f,
        640f, 800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3200f
    )

    /** Perceptually uniform Kelvin slider law (QA Phase 6): slider lives in mired space. */
    fun miredFromKelvin(kelvin: Int): Int = 1_000_000 / kelvin.coerceIn(KELVIN_MIN, KELVIN_MAX)
    fun kelvinFromMired(mired: Int): Int = (1_000_000 / mired.coerceIn(100, 400)).coerceIn(KELVIN_MIN, KELVIN_MAX)
    fun miredMin(): Int = miredFromKelvin(KELVIN_MAX)
    fun miredMax(): Int = miredFromKelvin(KELVIN_MIN)

    /** Gyro-EIS crop: 10% in (0.9 scale) for counter-rotation headroom. */
    const val EIS_CROP_SCALE = 0.9f

    /** Histogram: 64 bins x RGB, compute dispatch cadence 10 fps. */
    const val HISTOGRAM_BINS = 64
    const val HISTOGRAM_INTERVAL_MS = 100L

    /** Audio: 48 kHz stereo 16-bit PCM, AAC-LC 256 kbps (stage5_media.md §5). */
    const val AUDIO_SAMPLE_RATE = 48000
    const val AUDIO_BITRATE = 256_000

    /** Full-scale dB reference for 16-bit PCM: 20*log10(32767) = 90.308998 dB. */
    const val DBFS_REF_16BIT = 90.308998f

    fun shutterAnglePresets(): FloatArray = floatArrayOf(45f, 90f, 144f, 172.8f, 180f, 270f, 360f)
    fun shutterSpeedDenominators(): IntArray = intArrayOf(24, 48, 50, 60, 100, 120, 250, 500, 1000, 4000, 8000)

    /** Angle-invariant exposure law (stage3 §2.2): exposureNs = 1e9 * angle / (360 * fps). */
    fun exposureNsForAngle(angleDegrees: Float, fps: Int): Long =
        Math.round(1_000_000_000.0 * angleDegrees / (360.0 * fps))

    /** exposureNs for shutter speed 1/x s. */
    fun exposureNsForSpeed(denominator: Int): Long = 1_000_000_000L / denominator

    /**
     * Clamp + 110 µs headroom snap against the frame duration (stage3_camera2.md §2.1).
     * If (1e9/fps) <= exposure + 110_000 then exposure = (1e9/fps) - 110_000.
     */
    fun applyHeadroomSnap(exposureNs: Long, fps: Int): Long {
        val frameNs = 1_000_000_000L / fps
        val clamped = exposureNs.coerceIn(EXPOSURE_MIN_NS, EXPOSURE_MAX_NS)
        return if (frameNs <= clamped + EXPOSURE_HEADROOM_NS) {
            (frameNs - EXPOSURE_HEADROOM_NS).coerceAtLeast(EXPOSURE_MIN_NS)
        } else clamped
    }

    fun formatTimecode(totalFrames: Long, fps: Int): String {
        val f = (totalFrames % fps).toInt()
        val totalSeconds = totalFrames / fps
        val s = (totalSeconds % 60).toInt()
        val m = ((totalSeconds / 60) % 60).toInt()
        val h = totalSeconds / 3600
        return String.format("%02d:%02d:%02d:%02d", h, m, s, f)
    }
}

/**
 * Persistent + runtime settings holder. One instance owned by [com.m14.cinepro.MainActivity].
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("m14_cinepro_settings", Context.MODE_PRIVATE)

    // ---- Recording format ----
    @Volatile var resolution: Models.ResPreset = Models.ResPreset.UHD_4K_24
        set(v) { field = v; persist("resolution", v.name) }
    @Volatile var bitrate: Models.BitratePreset = Models.BitratePreset.M80
        set(v) { field = v; persist("bitrate", v.name) }

    // ---- Exposure (angle-invariant law) ----
    @Volatile var shutterMode: Models.ShutterMode = Models.ShutterMode.ANGLE
        set(v) { field = v; persist("shutterMode", v.name) }
    @Volatile var shutterAngleDeg: Float = 180f
        set(v) { field = v; persistFloat("shutterAngleDeg", v) }
    @Volatile var shutterSpeedDenominator: Int = 48
        set(v) { field = v; persistInt("shutterSpeedDenominator", v) }
    @Volatile var iso: Int = 100
        set(v) { field = v; persistInt("iso", v) }
    @Volatile var manualExposure: Boolean = false
        set(v) { field = v; persist("manualExposure", v) }
    @Volatile var evCompensation: Float = 0f
        set(v) { field = v; persistFloat("ev", v) }
    @Volatile var aeLock: Boolean = false
        set(v) { field = v; persist("aeLock", v) }
    @Volatile var antiBanding: Models.AntiBandingMode = Models.AntiBandingMode.AUTO
        set(v) { field = v; persist("antiBanding", v.name) }
    /** Last detected STATISTICS_SCENE_FLICKER (0 none, 1 = 50 Hz, 2 = 60 Hz). */
    @Volatile var sceneFlicker: Int = 0

    // ---- White balance ----
    @Volatile var wbPreset: Models.WbPreset = Models.WbPreset.AUTO
        set(v) { field = v; persist("wbPreset", v.name) }
    @Volatile var kelvin: Int = 5600
        set(v) { field = v; persistInt("kelvin", v) }
    @Volatile var tint: Int = 0
        set(v) { field = v; persistInt("tint", v) }
    @Volatile var awbLock: Boolean = false
        set(v) { field = v; persist("awbLock", v) }

    // ---- Focus markers F1/F2/F3 (normalized viewfinder coords; null = unset) ----
    val focusMarkers: Array<FloatArray?> = arrayOfNulls(3)

    // ---- Viewfinder effects ----
    @Volatile var peakingMode: Models.PeakingMode = Models.PeakingMode.OFF
        set(v) { field = v; persist("peakingMode", v.name) }
    @Volatile var peakingStrength: Float = Models.PEAKING_DEFAULT_UI
        set(v) { field = v; persistFloat("peakingStrength", v) }
    @Volatile var peakingColor: Models.PeakingColor = Models.PeakingColor.RED
        set(v) { field = v; persist("peakingColor", v.name) }
    @Volatile var zebraMode: Models.ZebraMode = Models.ZebraMode.OFF
        set(v) { field = v; persist("zebraMode", v.name) }
    @Volatile var zebraThresholdUi: Float = Models.ZEBRA_DEFAULT_UI_THRESHOLD
        set(v) { field = v; persistFloat("zebraThresholdUi", v) }
    @Volatile var falseColorOn: Boolean = false
        set(v) { field = v; persist("falseColorOn", v) }
    @Volatile var lutPreset: Models.LutPreset = Models.LutPreset.OFF
        set(v) { field = v; persist("lutPreset", v.name) }
    @Volatile var importedLutPath: String? = null
        set(v) { field = v; persist("importedLutPath", v ?: "") }
    @Volatile var guide: Models.GuidePreset = Models.GuidePreset.OFF
        set(v) { field = v; persist("guide", v.name) }
    @Volatile var grid: Models.GridOverlay = Models.GridOverlay.NONE
        set(v) { field = v; persist("grid", v.name) }
    @Volatile var desqueeze: Models.DesqueezePreset = Models.DesqueezePreset.X100
        set(v) { field = v; persist("desqueeze", v.name) }

    // ---- Digital zoom + preview flip (QA Phase 1/6) ----
    /** CONTROL_ZOOM_RATIO 1.0x..8.0x; setter clamps so a corrupted pref can never wedge the HAL. */
    @Volatile var zoomRatio: Float = Models.ZOOM_DEFAULT
        set(v) {
            val clamped = if (v.isNaN() || v.isInfinite()) Models.ZOOM_DEFAULT
            else v.coerceIn(Models.ZOOM_MIN, Models.ZOOM_MAX)
            field = clamped; persistFloat("zoomRatio", clamped)
        }
    /** Operator 180° preview flip (rig / DOF adapter); rotates the GL counter-rotation. */
    @Volatile var previewFlip180: Boolean = false
        set(v) { field = v; persist("previewFlip180", v) }
    /** Smooth 1.5 s servo rack-zoom for the focal-length chips (QA Phase 6). */
    @Volatile var rackZoomOn: Boolean = false
        set(v) { field = v; persist("rackZoomOn", v) }

    // ---- Transport ----
    @Volatile var eisOn: Boolean = false
        set(v) { field = v; persist("eisOn", v) }
    @Volatile var torchOn: Boolean = false
        set(v) { field = v; persist("torchOn", v) }
    @Volatile var audioMetering: Models.AudioMeteringMode = Models.AudioMeteringMode.VU_18DBFS
        set(v) { field = v; persist("audioMetering", v.name) }

    // ---- Slate (stage5_media.md BmdMetadata registry) ----
    @Volatile var reel: Int = 1
        set(v) { field = v; persistInt("reel", v) }
    @Volatile var scene: String = "1"
        set(v) { field = v; persist("scene", v) }
    @Volatile var take: Int = 1
        set(v) { field = v; persistInt("take", v) }
    @Volatile var goodTake: Boolean = false
        set(v) { field = v; persist("goodTake", v) }
    @Volatile var director: String = ""
        set(v) { field = v; persist("director", v) }
    @Volatile var operator: String = ""
        set(v) { field = v; persist("operator", v) }

    // ---- Convenience derivations (angle-invariant shutter law) ----

    val fps: Int get() = resolution.fps

    /** Current requested exposure in ns, per the active shutter mode. */
    fun requestedExposureNs(): Long = when (shutterMode) {
        Models.ShutterMode.ANGLE -> Models.exposureNsForAngle(shutterAngleDeg, fps)
        Models.ShutterMode.SPEED -> Models.exposureNsForSpeed(shutterSpeedDenominator)
    }

    /** Clamped + headroom-snapped exposure actually injected into the HAL. */
    fun effectiveExposureNs(): Long = Models.applyHeadroomSnap(requestedExposureNs(), fps)

    /** Shutter display string for the HUD pill. */
    fun shutterLabel(): String = when (shutterMode) {
        Models.ShutterMode.ANGLE -> String.format("%.1f°", shutterAngleDeg)
        Models.ShutterMode.SPEED -> "1/$shutterSpeedDenominator s"
    }

    fun isoLabel(): String = if (manualExposure) "ISO $iso" else "ISO AUTO"

    fun wbLabel(): String = when (wbPreset) {
        Models.WbPreset.AUTO -> "AWB"
        Models.WbPreset.KELVIN -> "${kelvin}K"
        Models.WbPreset.DAYLIGHT -> "Daylight"
        Models.WbPreset.CLOUDY_DAYLIGHT -> "Cloudy"
        Models.WbPreset.INCANDESCENT -> "Tungsten"
        Models.WbPreset.FLUORESCENT -> "Fluorescent"
    }

    /** Snapshot of the settings relevant to clip metadata / slate. */
    fun describeForMetadata(): String =
        "${resolution.label} ${fps}fps ${shutterLabel()} ${isoLabel()} ${wbLabel()} LUT=${lutPreset.name}"

    // ---- Persistence helpers ----

    private fun persist(key: String, value: String) = prefs.edit().putString(key, value).apply()
    private fun persistInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    private fun persistFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()
    private fun persist(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    init {
        fun str(k: String, d: String) = prefs.getString(k, d) ?: d
        resolution = runCatching { Models.ResPreset.valueOf(str("resolution", "UHD_4K_24")) }
            .getOrDefault(Models.ResPreset.UHD_4K_24)
        bitrate = runCatching { Models.BitratePreset.valueOf(str("bitrate", "M80")) }
            .getOrDefault(Models.BitratePreset.M80)
        shutterMode = runCatching { Models.ShutterMode.valueOf(str("shutterMode", "ANGLE")) }
            .getOrDefault(Models.ShutterMode.ANGLE)
        shutterAngleDeg = prefs.getFloat("shutterAngleDeg", 180f)
        shutterSpeedDenominator = prefs.getInt("shutterSpeedDenominator", 48)
        iso = prefs.getInt("iso", 100)
        manualExposure = prefs.getBoolean("manualExposure", false)
        evCompensation = prefs.getFloat("ev", 0f)
        aeLock = prefs.getBoolean("aeLock", false)
        antiBanding = runCatching { Models.AntiBandingMode.valueOf(str("antiBanding", "AUTO")) }
            .getOrDefault(Models.AntiBandingMode.AUTO)
        wbPreset = runCatching { Models.WbPreset.valueOf(str("wbPreset", "AUTO")) }
            .getOrDefault(Models.WbPreset.AUTO)
        kelvin = prefs.getInt("kelvin", 5600)
        tint = prefs.getInt("tint", 0)
        peakingMode = runCatching { Models.PeakingMode.valueOf(str("peakingMode", "OFF")) }
            .getOrDefault(Models.PeakingMode.OFF)
        peakingStrength = prefs.getFloat("peakingStrength", Models.PEAKING_DEFAULT_UI)
        peakingColor = Models.PeakingColor.fromName(str("peakingColor", "RED"))
        zebraMode = runCatching { Models.ZebraMode.valueOf(str("zebraMode", "OFF")) }
            .getOrDefault(Models.ZebraMode.OFF)
        zebraThresholdUi = prefs.getFloat("zebraThresholdUi", Models.ZEBRA_DEFAULT_UI_THRESHOLD)
        falseColorOn = prefs.getBoolean("falseColorOn", false)
        lutPreset = runCatching { Models.LutPreset.valueOf(str("lutPreset", "OFF")) }
            .getOrDefault(Models.LutPreset.OFF)
        importedLutPath = prefs.getString("importedLutPath", null)
        guide = runCatching { Models.GuidePreset.valueOf(str("guide", "OFF")) }
            .getOrDefault(Models.GuidePreset.OFF)
        grid = runCatching { Models.GridOverlay.valueOf(str("grid", "NONE")) }
            .getOrDefault(Models.GridOverlay.NONE)
        desqueeze = runCatching { Models.DesqueezePreset.valueOf(str("desqueeze", "X100")) }
            .getOrDefault(Models.DesqueezePreset.X100)
        eisOn = prefs.getBoolean("eisOn", false)
        audioMetering = runCatching {
            Models.AudioMeteringMode.valueOf(str("audioMetering", "VU_18DBFS"))
        }.getOrDefault(Models.AudioMeteringMode.VU_18DBFS)
        reel = prefs.getInt("reel", 1)
        scene = str("scene", "1")
        take = prefs.getInt("take", 1)
        director = str("director", "")
        operator = str("operator", "")
        zoomRatio = prefs.getFloat("zoomRatio", Models.ZOOM_DEFAULT)
        previewFlip180 = prefs.getBoolean("previewFlip180", false)
        rackZoomOn = prefs.getBoolean("rackZoomOn", false)
        sanitize()
    }

    /**
     * Corrupted-settings recovery (QA Phase 4): every numeric is coerced back into its legal
     * range; out-of-range values are written back sanitized because each setter persists.
     */
    private fun sanitize() {
        shutterAngleDeg = shutterAngleDeg.coerceIn(45f, 360f)
        shutterSpeedDenominator = shutterSpeedDenominator.coerceIn(1, 8000)
        iso = iso.coerceIn(Models.ISO_MIN, Models.ISO_MAX)
        evCompensation = evCompensation.coerceIn(Models.EV_MIN, Models.EV_MAX)
        kelvin = kelvin.coerceIn(Models.KELVIN_MIN, Models.KELVIN_MAX)
        tint = tint.coerceIn(Models.TINT_MIN, Models.TINT_MAX)
        peakingStrength = peakingStrength.coerceIn(0f, 1f)
        zebraThresholdUi = zebraThresholdUi.coerceIn(0.75f, 1.0f)
        zoomRatio = zoomRatio
        reel = reel.coerceAtLeast(1)
        take = take.coerceAtLeast(1)
        if (importedLutPath?.isBlank() == true) importedLutPath = null
    }

    /**
     * Factory reset (QA Phase 4): wipes m14_cinepro_settings.xml and restores every in-memory
     * field to its default. Callers must rebuild the session + GL uniforms afterwards.
     */
    fun resetAll() {
        prefs.edit().clear().commit()
        focusMarkers[0] = null
        focusMarkers[1] = null
        focusMarkers[2] = null
        resolution = Models.ResPreset.UHD_4K_24
        bitrate = Models.BitratePreset.M80
        shutterMode = Models.ShutterMode.ANGLE
        shutterAngleDeg = 180f
        shutterSpeedDenominator = 48
        iso = 100
        manualExposure = false
        evCompensation = 0f
        aeLock = false
        antiBanding = Models.AntiBandingMode.AUTO
        wbPreset = Models.WbPreset.AUTO
        kelvin = 5600
        tint = 0
        awbLock = false
        peakingMode = Models.PeakingMode.OFF
        peakingStrength = Models.PEAKING_DEFAULT_UI
        peakingColor = Models.PeakingColor.RED
        zebraMode = Models.ZebraMode.OFF
        zebraThresholdUi = Models.ZEBRA_DEFAULT_UI_THRESHOLD
        falseColorOn = false
        lutPreset = Models.LutPreset.OFF
        importedLutPath = null
        guide = Models.GuidePreset.OFF
        grid = Models.GridOverlay.NONE
        desqueeze = Models.DesqueezePreset.X100
        eisOn = false
        torchOn = false
        zoomRatio = Models.ZOOM_DEFAULT
        previewFlip180 = false
        rackZoomOn = false
        sceneFlicker = 0
    }

    /** Persist F-marker positions (normalized 0..1 x/y, or null to clear). */
    fun saveMarker(index: Int, x: Float?, y: Float?) {
        val e = prefs.edit()
        if (x == null || y == null) {
            focusMarkers[index] = null
            e.remove("marker${index}x").remove("marker${index}y")
        } else {
            focusMarkers[index] = floatArrayOf(x, y)
            e.putFloat("marker${index}x", x).putFloat("marker${index}y", y)
        }
        e.apply()
    }

    init {
        for (i in 0..2) {
            if (prefs.contains("marker${i}x")) {
                focusMarkers[i] = floatArrayOf(
                    prefs.getFloat("marker${i}x", 0.5f),
                    prefs.getFloat("marker${i}y", 0.5f)
                )
            }
        }
    }
}
