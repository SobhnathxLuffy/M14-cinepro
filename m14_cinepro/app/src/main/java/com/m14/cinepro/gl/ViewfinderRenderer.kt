package com.m14.cinepro.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES32
import android.opengl.GLES11Ext
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.m14.cinepro.core.EngineListener
import com.m14.cinepro.core.Models
import com.m14.cinepro.core.SettingsStore
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.nio.charset.StandardCharsets
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * M14 CinePro — the GL viewfinder renderer (stage4_shaders.md §1, §7.4, §10.2, §10.3, §10.5).
 *
 * Per frame: SurfaceTexture.updateTexImage() -> compose texMatrix = ST * EIS (EIS right
 * operand, applied to texcoords first) -> master program full-screen quad ->
 * every Models.HISTOGRAM_INTERVAL_MS draw the graded overlay-free frame into the analysis FBO
 * and dispatch the 64-bin RGB histogram compute + readback -> EngineListener.onHistogram.
 *
 * Master fragment assembly (§10.2 EXACT): assets/glsl/fragment_header.glsl + wb_trim_effect +
 * luminance_function + lut_3d_effect + false_color_effect_fast + zebra_effect +
 * focus_assist_effect + generated main(). All shader source is loaded from assets at runtime —
 * never duplicated in Kotlin.
 *
 * Uniform laws (§10.3): zebraThreshold = UI * Models.ZEBRA_THRESHOLD_SCALE_709 (0.93,
 * Rec.709 display profile), peakingThreshold = UI * Models.PEAKING_UI_SCALE (0.04),
 * zebraShift marches +1.0/frame wrapping at 2*zebraWidth, luminance LUT always visible
 * (33^3, unit 3), creative LUT on unit 2 with half-voxel scale/offset, colorSpace = 0
 * (Rec.709 SDR).
 *
 * Renderer holds no sensor and no camera-package dependencies: EIS matrices and WB gains
 * arrive via provider lambdas wired by MainActivity. Renderer thread priority -19 (ground
 * truth law), applied once on the first onDrawFrame.
 */
class ViewfinderRenderer(
    private val context: Context,
    private val settings: SettingsStore,
    private val listener: EngineListener,
    private val lutEngine: LutEngine,
    private val onTextureReady: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "M14GL"

        /** Full-screen quad, TRIANGLE_STRIP order (-1,-1),(1,-1),(-1,1),(1,1), vec4 attribute. */
        private val QUAD_COORDS = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 0f, 1f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f
        )

        /** Generated main() — stage4 §10.2 exact statement order (pre-LUT luminance law). */
        private const val MASTER_MAIN =
            "void main() {\n" +
                "    vec4 inColor = texture(cameraTexture, cameraTexCoordinates);\n" +
                "    vec4 outColor = inColor;\n" +
                "    if (wbTrimVisible == 1) outColor = wbTrim(outColor);\n" +
                "    float inLuminance = getLuminance(inColor);\n" +
                "    if (lut3DVisible == 1) outColor = lut3D(outColor);\n" +
                "    if (areEffectsVisible == 1) {\n" +
                "        if (falseColorVisible == 1) outColor = falseColor(inLuminance);\n" +
                "        if (zebrasVisible == 1) outColor = zebras(outColor, inLuminance);\n" +
                "        if (focusAssistVisible == 1) outColor = focusAssist(outColor);\n" +
                "    }\n" +
                "    fragColor = outColor;\n" +
                "}\n"

        private val UNIFORM_NAMES = arrayOf(
            "cameraTexture", "texMatrix", "areEffectsVisible",
            "wbTrimVisible", "wbGains",
            "luminanceVisible", "luminanceScale", "luminanceOffset", "luminanceTexture",
            "lut3DVisible", "lut3DScale", "lut3DOffset", "lut3DTexture",
            "falseColorVisible", "colorSpace",
            "zebrasVisible", "midGreyMode", "zebraThreshold", "zebraShift", "zebraWidth",
            "zebraColor", "outWidth", "outHeight",
            "focusAssistVisible", "enableColoredLines", "lineColor", "peakingThreshold"
        )
    }

    /** Back-reference to the hosting GLSurfaceView (for requestRender on frame available). */
    @Volatile
    internal var host: GLSurfaceView? = null

    /**
     * EIS counter-rotation matrix provider, set by MainActivity (GyroEisController owns the
     * sensor math). Return null when EIS is off. Column-major 4x4 like SurfaceTexture's ST
     * matrix; composed as combined = ST * EIS so EIS is the right operand (applied to
     * texcoords first).
     */
    @Volatile
    var eisMatrixProvider: (() -> FloatArray?)? = null

    /**
     * White-balance shader gains provider, set by MainActivity (wired to
     * WhiteBalanceController.normalizedShaderGains(), green == 1.0). Null -> unity trim.
     */
    @Volatile
    var wbGainsProvider: (() -> FloatArray)? = null

    // GL objects / program state
    private var program = 0
    private val uniformCache = HashMap<String, Int>()
    private var positionAttrib = -1
    private var quadVbo = 0
    private var cameraOesTex = 0
    private var lutTex3D = 0
    private var lumTex3D = 0
    private var uploadedLutSize = 0
    private var uploadedLumSize = 0
    private var surfaceTexture: SurfaceTexture? = null

    // Frame state
    private val stMatrix = FloatArray(16)
    private val combinedMatrix = FloatArray(16)
    private var outW = 0
    private var outH = 0
    private val histogramEngine = HistogramEngine(context)
    private var lastHistogramMs = 0L
    private var zebraShift = 0f
    private var frameCounter = 0L
    private var priorityApplied = false

    // LUT state
    @Volatile
    private var pendingLutPreset: Models.LutPreset? = null
    private var currentLut: LutEngine.Lut3D? = null
    private var currentLutPreset: Models.LutPreset? = null
    private var currentLutPathStamp: String? = null

    // -----------------------------------------------------------------------------------------
    // GLSurfaceView.Renderer
    // -----------------------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.i(TAG, "M14GL onSurfaceCreated: ${GLES32.glGetString(GLES32.GL_RENDERER)}")

        GLES32.glDisable(GLES32.GL_DEPTH_TEST)
        GLES32.glDisable(GLES32.GL_BLEND)
        GLES32.glClearColor(0f, 0f, 0f, 1f)

        buildProgram()

        // Full-viewport quad VBO.
        val vboArr = IntArray(1)
        GLES32.glGenBuffers(1, vboArr, 0)
        quadVbo = vboArr[0]
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, quadVbo)
        GLES32.glBufferData(
            GLES32.GL_ARRAY_BUFFER, QUAD_COORDS.size * 4,
            FloatBuffer.wrap(QUAD_COORDS), GLES32.GL_STATIC_DRAW
        )
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, 0)

        // Camera OES texture + SurfaceTexture (callback handed to CameraEngine by MainActivity).
        val texArr = IntArray(1)
        GLES32.glGenTextures(1, texArr, 0)
        cameraOesTex = texArr[0]
        GLES32.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraOesTex)
        GLES32.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        val st = SurfaceTexture(cameraOesTex)
        surfaceTexture = st
        st.setOnFrameAvailableListener { host?.requestRender() }

        // 3D LUT textures: 2^3 identity fallback (LUT-off path never changes the shader path,
        // stage4 §12.6) + the always-visible 33^3 luminance bake.
        val lutTexArr = IntArray(1)
        GLES32.glGenTextures(1, lutTexArr, 0)
        lutTex3D = lutTexArr[0]
        uploadedLutSize = 0
        uploadLutTexture(lutTex3D, identityVoxel2x3(), 2)
        currentLut = null
        currentLutPreset = null
        currentLutPathStamp = null

        val lumTexArr = IntArray(1)
        GLES32.glGenTextures(1, lumTexArr, 0)
        lumTex3D = lumTexArr[0]
        uploadedLumSize = 0
        refreshLuminanceLut()

        // Seed the creative LUT from the persisted settings for the very first frame.
        pendingLutPreset = settings.lutPreset

        // MUST fire once per created SurfaceTexture, from the GL thread.
        onTextureReady(st)
        GlslSupport.checkGlErrors(TAG, "onSurfaceCreated")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        outW = width
        outH = height
        GLES32.glViewport(0, 0, width, height)
        histogramEngine.setup(width, height)
        Log.i(TAG, "M14GL onSurfaceChanged: ${width}x${height}")
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!priorityApplied) {
            // Ground-truth law: renderer thread prio -19 (stage4 §10.1 ax0 HandlerThread).
            priorityApplied = try {
                Process.setThreadPriority(Process.myTid(), -19)
                true
            } catch (t: Throwable) {
                try {
                    Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_URGENT_DISPLAY)
                } catch (ignored: Throwable) {
                }
                Log.w(TAG, "M14GL setThreadPriority(-19) failed: ${t.message}")
                true
            }
        }

        resolvePendingLut()

        val st = surfaceTexture
        if (st == null || outW <= 0 || outH <= 0 || program == 0 || positionAttrib < 0) {
            GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
            GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
            return
        }

        st.updateTexImage()
        st.getTransformMatrix(stMatrix)
        val eis = eisMatrixProvider?.invoke()
        if (eis != null && eis.size >= 16) {
            Matrix.multiplyMM(combinedMatrix, 0, stMatrix, 0, eis, 0)
        } else {
            System.arraycopy(stMatrix, 0, combinedMatrix, 0, 16)
        }

        // ---- Screen pass: master program, all effects per settings ----
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
        GLES32.glViewport(0, 0, outW, outH)
        GLES32.glUseProgram(program)

        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraOesTex)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE2)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_3D, lutTex3D)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE3)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_3D, lumTex3D)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)

        uMat4("texMatrix", combinedMatrix)
        applyUniforms(effectsVisible = true)
        drawQuad()

        // ---- Histogram pass: graded (LUT-applied), overlay-free, at 10 fps ----
        val now = SystemClock.elapsedRealtime()
        if (histogramEngine.isReady() && now - lastHistogramMs >= Models.HISTOGRAM_INTERVAL_MS) {
            lastHistogramMs = now
            GLES32.glUseProgram(program)
            applyUniforms(effectsVisible = false)
            histogramEngine.drawGradedFrame { drawQuad() }
            val bins = histogramEngine.dispatchAndRead()
            if (bins != null) listener.onHistogram(bins)
        }

        // Zebra marching law: +1.0 px per frame, wrap at 2 * zebraWidth (16.0).
        zebraShift += 1.0f
        if (zebraShift >= 2f * Models.ZEBRA_WIDTH_PX) zebraShift -= 2f * Models.ZEBRA_WIDTH_PX

        frameCounter++
        if (frameCounter % 90L == 0L) GlslSupport.checkGlErrors(TAG, "frame $frameCounter")
    }

    // Note: GLSurfaceView.Renderer has no onSurfaceDestroyed hook; GL objects are owned by the
    // EGL context which is preserved via setPreserveEGLContextOnPause, and full teardown happens
    // in releaseViewfinder() called from GlViewfinder.releaseViewfinder().

    // -----------------------------------------------------------------------------------------
    // Public control surface (GlViewfinder forwards, may arrive queued on the GL thread)
    // -----------------------------------------------------------------------------------------

    /** Queue a LUT preset change; resolved + uploaded at the start of the next onDrawFrame. */
    fun requestLut(preset: Models.LutPreset) {
        pendingLutPreset = preset
    }

    /** Re-bake + re-upload the luminance 3D LUT (glTexSubImage3D, same 33^3 size). */
    fun refreshLuminanceLut() {
        if (lumTex3D == 0) return
        val lut = lutEngine.bakeLuminanceLut()
        uploadLutTexture(lumTex3D, lut.rgbaHalf, lut.size)
        Log.i(TAG, "M14GL luminance LUT baked (${lut.size}^3)")
    }

    /** Full GL teardown (GL thread; called via GlViewfinder.releaseViewfinder). */
    fun releaseGl() {
        surfaceTexture?.release()
        surfaceTexture = null
        if (cameraOesTex != 0) {
            GLES32.glDeleteTextures(1, intArrayOf(cameraOesTex), 0)
            cameraOesTex = 0
        }
        if (quadVbo != 0) {
            GLES32.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
            quadVbo = 0
        }
        if (lutTex3D != 0) {
            GLES32.glDeleteTextures(1, intArrayOf(lutTex3D), 0)
            lutTex3D = 0
        }
        if (lumTex3D != 0) {
            GLES32.glDeleteTextures(1, intArrayOf(lumTex3D), 0)
            lumTex3D = 0
        }
        if (program != 0) {
            GLES32.glDeleteProgram(program)
            program = 0
        }
        uniformCache.clear()
        positionAttrib = -1
        currentLut = null
        currentLutPreset = null
        currentLutPathStamp = null
        histogramEngine.release()
        Log.i(TAG, "M14GL renderer released")
    }

    // -----------------------------------------------------------------------------------------
    // Master program assembly (stage4 §10.2 EXACT include order + generated main)
    // -----------------------------------------------------------------------------------------

    private fun buildProgram() {
        val vertexSrc = GlslSupport.readAsset(context, "glsl/transform_vertex.glsl")
        val fragmentSrc = StringBuilder()
            .append(GlslSupport.readAsset(context, "glsl/fragment_header.glsl"))
            .append(GlslSupport.readAsset(context, "glsl/wb_trim_effect.glsl"))
            .append(GlslSupport.readAsset(context, "glsl/luminance_function.glsl"))
            .append(GlslSupport.readAsset(context, "glsl/lut_3d_effect.glsl"))
            .append(GlslSupport.readAsset(context, "glsl/false_color_effect_fast.glsl"))
            .append(GlslSupport.readAsset(context, "glsl/zebra_effect.glsl"))
            .append(GlslSupport.readAsset(context, "glsl/focus_assist_effect.glsl"))
            .append(MASTER_MAIN)
            .toString()

        val vs = GlslSupport.compile(GLES32.GL_VERTEX_SHADER, vertexSrc, "transform_vertex")
        val fs = GlslSupport.compile(GLES32.GL_FRAGMENT_SHADER, fragmentSrc, "master_fragment")
        val p = GLES32.glCreateProgram()
        GLES32.glAttachShader(p, vs)
        GLES32.glAttachShader(p, fs)
        GLES32.glLinkProgram(p)
        val linked = IntArray(1)
        GLES32.glGetProgramiv(p, GLES32.GL_LINK_STATUS, linked, 0)
        GLES32.glDeleteShader(vs)
        GLES32.glDeleteShader(fs)
        if (linked[0] == 0) {
            val log = GLES32.glGetProgramInfoLog(p)
            GLES32.glDeleteProgram(p)
            // Fail fast — no silent black screen (task law).
            throw IllegalStateException("M14GL master program link failed: $log")
        }
        program = p
        positionAttrib = GLES32.glGetAttribLocation(p, "vPosition")

        uniformCache.clear()
        GLES32.glUseProgram(p)
        for (name in UNIFORM_NAMES) uniformCache[name] = GLES32.glGetUniformLocation(p, name)
        // Sampler bindings (fixed units: 0 = camera OES, 2 = creative LUT, 3 = luminance LUT).
        u1i("cameraTexture", 0)
        u1i("lut3DTexture", 2)
        u1i("luminanceTexture", 3)
        GlslSupport.checkGlErrors(TAG, "buildProgram")
    }

    // -----------------------------------------------------------------------------------------
    // Uniform uploads (stage4 §10.3 exact setters)
    // -----------------------------------------------------------------------------------------

    /**
     * Upload every uniform from SettingsStore (volatile reads, every frame).
     * [effectsVisible] = true for the screen pass, false for the histogram FBO pass
     * (areEffectsVisible=0 -> "LUT-applied, overlay-free" law; wbTrim + 3D LUT stay live).
     */
    private fun applyUniforms(effectsVisible: Boolean) {
        val anyOverlay = settings.peakingMode != Models.PeakingMode.OFF ||
            settings.zebraMode != Models.ZebraMode.OFF ||
            settings.falseColorOn
        u1i("areEffectsVisible", if (effectsVisible && anyOverlay) 1 else 0)

        // White balance trim (first in chain; not gated by areEffectsVisible).
        u1i("wbTrimVisible", if (settings.wbPreset == Models.WbPreset.KELVIN) 1 else 0)
        val gains = wbGainsProvider?.invoke()
        if (gains != null && gains.size >= 3) {
            u3f("wbGains", gains[0], gains[1], gains[2])
        } else {
            u3f("wbGains", 1f, 1f, 1f)
        }

        // Luminance LUT: always visible, fixed 33^3 half-voxel correction.
        u1i("luminanceVisible", 1)
        u1f("luminanceScale", lutEngine.halfVoxelScale(LutEngine.LUT_SIZE))
        u1f("luminanceOffset", lutEngine.halfVoxelOffset(LutEngine.LUT_SIZE))

        // Creative 3D LUT.
        val lut = currentLut
        u1i("lut3DVisible", if (lut != null) 1 else 0)
        if (lut != null) {
            u1f("lut3DScale", lutEngine.halfVoxelScale(lut.size))
            u1f("lut3DOffset", lutEngine.halfVoxelOffset(lut.size))
        }

        // False color: fast variant consumes the PRE-LUT luminance (inLuminance in main()).
        u1i("falseColorVisible", if (settings.falseColorOn) 1 else 0)
        u1i("colorSpace", 0) // Rec.709 SDR

        // Zebras.
        u1i("zebrasVisible", if (settings.zebraMode != Models.ZebraMode.OFF) 1 else 0)
        val zebra = zebraUniformParams()
        u1i("midGreyMode", zebra.first)
        u1f("zebraThreshold", zebra.second)
        u1f("zebraShift", zebraShift)
        u1f("zebraWidth", Models.ZEBRA_WIDTH_PX)
        u4f("zebraColor", 1.0f, 0.85f, 0.27f, 1.0f) // YELLOW (task law)
        u1i("outWidth", outW)
        u1i("outHeight", outH)

        // Focus peaking.
        u1i("focusAssistVisible", if (settings.peakingMode != Models.PeakingMode.OFF) 1 else 0)
        u1i("enableColoredLines", if (settings.peakingMode == Models.PeakingMode.COLORED_LINES) 1 else 0)
        val pc = settings.peakingColor.rgba
        u4f("lineColor", pc[0], pc[1], pc[2], pc[3])
        u1f("peakingThreshold", settings.peakingStrength * Models.PEAKING_UI_SCALE)
    }

    /**
     * Zebra mode -> (midGreyMode, zebraThreshold).
     *  Z95/Z90: midGreyMode 0, anchored thresholds 0.95 / 0.90 scaled by the Rec.709 display
     *  profile factor (Models.ZEBRA_THRESHOLD_SCALE_709 = 0.93, stage4 §10.3).
     *  MID_GREY / MID_GREY_PLUS1: midGreyMode 1 / 2 with zebraThreshold = 0.0 (threshold
     *  irrelevant when the mid-grey band drives — task law; NOTE the include ORs both
     *  conditions, flagged in the worklog).
     *  OFF / custom: the persisted UI threshold * 0.93 (values unused while visible = 0).
     */
    private fun zebraUniformParams(): Pair<Int, Float> = when (settings.zebraMode) {
        Models.ZebraMode.Z95 -> 0 to (0.95f * Models.ZEBRA_THRESHOLD_SCALE_709)
        Models.ZebraMode.Z90 -> 0 to (0.90f * Models.ZEBRA_THRESHOLD_SCALE_709)
        Models.ZebraMode.MID_GREY -> 1 to 0.0f
        Models.ZebraMode.MID_GREY_PLUS1 -> 2 to 0.0f
        Models.ZebraMode.OFF -> 0 to (settings.zebraThresholdUi * Models.ZEBRA_THRESHOLD_SCALE_709)
    }

    // -----------------------------------------------------------------------------------------
    // LUT resolution + upload (stage4 §7.4)
    // -----------------------------------------------------------------------------------------

    private fun resolvePendingLut() {
        val preset = pendingLutPreset ?: return
        pendingLutPreset = null
        val pathStamp = lutEngine.importedPath
        if (currentLutPreset == preset && currentLutPathStamp == pathStamp && lutTex3D != 0) return
        currentLutPreset = preset
        currentLutPathStamp = pathStamp

        val lut = if (lutTex3D != 0) lutEngine.generate(preset) else null
        if (lut != null) {
            uploadLutTexture(lutTex3D, lut.rgbaHalf, lut.size)
            currentLut = lut
            Log.i(TAG, "M14GL LUT '${lut.title}' active (${lut.size}^3)")
        } else {
            // LUT off: 2^3 identity data stays in the texture, lut3DVisible = 0.
            uploadLutTexture(lutTex3D, identityVoxel2x3(), 2)
            currentLut = null
        }
    }

    /**
     * RGBA16F GL_TEXTURE_3D upload: GL_TEXTURE_3D / GL_RGBA16F / GL_RGBA / GL_HALF_FLOAT,
     * LINEAR filters, CLAMP_TO_EDGE on S/T/R; glTexSubImage3D when the size is unchanged
     * (LUT swap law, stage4 §7.4 ef2.i).
     */
    private fun uploadLutTexture(texId: Int, rgbaHalf: ShortArray, size: Int) {
        if (texId == 0) return
        GLES32.glActiveTexture(GLES32.GL_TEXTURE2)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_3D, texId)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_3D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_3D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_3D, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_3D, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_3D, GLES32.GL_TEXTURE_WRAP_R, GLES32.GL_CLAMP_TO_EDGE)
        val buffer = ShortBuffer.wrap(rgbaHalf)
        // glTexSubImage3D when the size is unchanged (LUT swap law, stage4 §7.4).
        val isSubUpload = (texId == lutTex3D && uploadedLutSize == size) ||
            (texId == lumTex3D && uploadedLumSize == size)
        if (isSubUpload) {
            GLES32.glTexSubImage3D(
                GLES32.GL_TEXTURE_3D, 0, 0, 0, 0, size, size, size,
                GLES32.GL_RGBA, GLES32.GL_HALF_FLOAT, buffer
            )
        } else {
            GLES32.glTexImage3D(
                GLES32.GL_TEXTURE_3D, 0, GLES32.GL_RGBA16F, size, size, size, 0,
                GLES32.GL_RGBA, GLES32.GL_HALF_FLOAT, buffer
            )
            if (texId == lutTex3D) uploadedLutSize = size
            if (texId == lumTex3D) uploadedLumSize = size
        }
        GLES32.glBindTexture(GLES32.GL_TEXTURE_3D, 0)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GlslSupport.checkGlErrors(TAG, "uploadLutTexture size=$size")
    }

    /** 2^3 identity LUT payload (LUT-off fallback, stage4 §12.6). */
    private fun identityVoxel2x3(): ShortArray {
        val data = ShortArray(2 * 2 * 2 * 4)
        var i = 0
        for (b in 0..1) for (g in 0..1) for (r in 0..1) {
            data[i++] = lutEngine.toHalf(r.toFloat())
            data[i++] = lutEngine.toHalf(g.toFloat())
            data[i++] = lutEngine.toHalf(b.toFloat())
            data[i++] = lutEngine.toHalf(1f)
        }
        return data
    }

    // -----------------------------------------------------------------------------------------
    // Draw helpers
    // -----------------------------------------------------------------------------------------

    private fun drawQuad() {
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, quadVbo)
        GLES32.glVertexAttribPointer(positionAttrib, 4, GLES32.GL_FLOAT, false, 0, 0)
        GLES32.glEnableVertexAttribArray(positionAttrib)
        GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, 4)
        GLES32.glBindBuffer(GLES32.GL_ARRAY_BUFFER, 0)
    }

    // -----------------------------------------------------------------------------------------
    // Uniform cache helpers
    // -----------------------------------------------------------------------------------------

    private fun loc(name: String): Int = uniformCache.getOrPut(name) {
        if (program != 0) GLES32.glGetUniformLocation(program, name) else -1
    }

    private fun u1i(name: String, v: Int) {
        val l = loc(name)
        if (l >= 0) GLES32.glUniform1i(l, v)
    }

    private fun u1f(name: String, v: Float) {
        val l = loc(name)
        if (l >= 0) GLES32.glUniform1f(l, v)
    }

    private fun u3f(name: String, a: Float, b: Float, c: Float) {
        val l = loc(name)
        if (l >= 0) GLES32.glUniform3f(l, a, b, c)
    }

    private fun u4f(name: String, a: Float, b: Float, c: Float, d: Float) {
        val l = loc(name)
        if (l >= 0) GLES32.glUniform4f(l, a, b, c, d)
    }

    private fun uMat4(name: String, m: FloatArray) {
        val l = loc(name)
        if (l >= 0) GLES32.glUniformMatrix4fv(l, 1, false, m, 0)
    }
}

/**
 * GLSL loader util (task law): asset read + compile/link with info-log checks; any failure
 * throws IllegalStateException carrying the GL log — fail fast, no silent black screen.
 */
internal object GlslSupport {

    fun readAsset(context: Context, path: String): String =
        context.assets.open(path).use { stream ->
            stream.readBytes().toString(StandardCharsets.UTF_8)
        }

    fun compile(type: Int, source: String, label: String): Int {
        val shader = GLES32.glCreateShader(type)
        if (shader == 0) throw IllegalStateException("M14GL glCreateShader failed [$label]")
        GLES32.glShaderSource(shader, source)
        GLES32.glCompileShader(shader)
        val status = IntArray(1)
        GLES32.glGetShaderiv(shader, GLES32.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES32.glGetShaderInfoLog(shader)
            GLES32.glDeleteShader(shader)
            throw IllegalStateException("M14GL shader compile failed [$label]: $log")
        }
        return shader
    }

    /** Drain + log pending GL errors (assert-free; called occasionally). */
    fun checkGlErrors(tag: String, where: String) {
        var error = GLES32.glGetError()
        if (error == GLES32.GL_NO_ERROR) return
        while (error != GLES32.GL_NO_ERROR) {
            Log.w(tag, "M14GL GL error 0x${Integer.toHexString(error)} at $where")
            error = GLES32.glGetError()
        }
    }
}
