package com.m14.cinepro.gl

import android.content.Context
import android.opengl.GLES31
import android.opengl.GLES32
import android.util.Log
import com.m14.cinepro.core.Models
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * M14 CinePro — GPU histogram engine (stage4_shaders.md §1.8 / §5 / §10.4).
 *
 * Renders the graded (LUT-applied, overlay-free) analysis frame into a downscaled RGBA8 FBO
 * (floor(viewport/16), even, min 1) and accumulates a 64-bin x RGB histogram with the
 * GLES 3.1 compute kernel from assets (%%BINS%% injected textually). Two-level accumulation
 * (shared-memory workgroup histogram -> one atomicAdd per bin into a std430 SSBO at binding 0);
 * the global buffer is zeroed on the CPU each dispatch. Readback maps the SSBO and hands a
 * fresh 768-byte little-endian ByteBuffer (192 uint32: R=[0,64) G=[64,128) B=[128,192)) to the
 * renderer, which forwards it to EngineListener.onHistogram.
 *
 * Compute capability guard: if the compute program cannot be built the engine disables itself
 * (isReady() == false) and logs — the viewfinder keeps rendering, only histogram/scene
 * detection are disabled (stage4 §10.4 law string).
 */
class HistogramEngine(private val context: Context) {

    companion object {
        private const val TAG = "M14GL"

        /** 64 bins x 3 channels x 4 bytes = 768. */
        private const val SSBO_BYTES = Models.HISTOGRAM_BINS * 3 * 4
        private const val WORK_GROUP = 16
    }

    private var program = 0
    private var uSrc = -1
    private var fbo = 0
    private var tex = 0
    private var ssbo = 0
    private var width = 0
    private var height = 0
    private var computeReady = false

    /** Analysis FBO dimensions (floor(viewport/16), even, min 1). */
    fun analysisWidth(): Int = width
    fun analysisHeight(): Int = height

    /** True when FBO + SSBO + compute program are live and usable. */
    fun isReady(): Boolean = computeReady && program != 0 && fbo != 0 && tex != 0 && ssbo != 0

    /**
     * Create / resize the analysis resources. Called from onSurfaceChanged with the viewport
     * size; recreates the FBO texture + SSBO when the derived analysis size changes, keeps the
     * compiled compute program across sizes.
     */
    fun setup(viewportW: Int, viewportH: Int) {
        val aw = analysisSize(viewportW)
        val ah = analysisSize(viewportH)
        if (isReady() && aw == width && ah == height) return

        releaseTargets(keepProgram = true)
        width = aw
        height = ah

        // Analysis color texture: GL_RGBA8, LINEAR, CLAMP_TO_EDGE (stage4 §10.4).
        val texArr = IntArray(1)
        GLES32.glGenTextures(1, texArr, 0)
        tex = texArr[0]
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, tex)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexImage2D(
            GLES32.GL_TEXTURE_2D, 0, GLES32.GL_RGBA8, width, height, 0,
            GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, null
        )

        val fboArr = IntArray(1)
        GLES32.glGenFramebuffers(1, fboArr, 0)
        fbo = fboArr[0]
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, fbo)
        GLES32.glFramebufferTexture2D(
            GLES32.GL_FRAMEBUFFER, GLES32.GL_COLOR_ATTACHMENT0, GLES32.GL_TEXTURE_2D, tex, 0
        )
        val status = GLES32.glCheckFramebufferStatus(GLES32.GL_FRAMEBUFFER)
        if (status != GLES32.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "M14GL histogram FBO incomplete: 0x${Integer.toHexString(status)}")
            releaseTargets(keepProgram = true)
            return
        }

        if (program == 0) buildComputeProgram()
        if (program == 0) {
            releaseTargets(keepProgram = true)
            return
        }

        // SSBO: 768 bytes, GL_DYNAMIC_READ, bound at binding 0 (std430 layout in the kernel).
        val ssboArr = IntArray(1)
        GLES31.glGenBuffers(1, ssboArr, 0)
        ssbo = ssboArr[0]
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssbo)
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, SSBO_BYTES, null, GLES31.GL_DYNAMIC_READ)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, ssbo)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)

        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
        computeReady = true
        GlslSupport.checkGlErrors(TAG, "histogram setup")
        Log.i(TAG, "M14GL histogram ready: analysis ${width}x${height}, bins=${Models.HISTOGRAM_BINS}")
    }

    /** floor(viewport/16), forced even, min 1 — exactly stage4 §10.4. */
    private fun analysisSize(viewport: Int): Int {
        val q = viewport / WORK_GROUP
        val even = (q / 2) * 2
        return even.coerceAtLeast(1)
    }

    private fun buildComputeProgram() {
        try {
            val src = GlslSupport.readAsset(context, "glsl/histogram_compute.glsl")
                .replace("%%BINS%%", Models.HISTOGRAM_BINS.toString())
            val shader = GlslSupport.compile(GLES31.GL_COMPUTE_SHADER, src, "histogram_compute")
            val p = GLES32.glCreateProgram()
            GLES32.glAttachShader(p, shader)
            GLES32.glLinkProgram(p)
            val linked = IntArray(1)
            GLES32.glGetProgramiv(p, GLES32.GL_LINK_STATUS, linked, 0)
            GLES32.glDeleteShader(shader)
            if (linked[0] == 0) {
                val log = GLES32.glGetProgramInfoLog(p)
                GLES32.glDeleteProgram(p)
                throw IllegalStateException("histogram_compute link failed: $log")
            }
            program = p
            uSrc = GLES32.glGetUniformLocation(p, "uSrc")
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "M14GL GPU histogram unavailable: compute shaders unsupported on this device. " +
                    "Histogram and scene detection are disabled.",
                t
            )
            program = 0
            uSrc = -1
        }
    }

    /**
     * Bind the analysis FBO + its viewport, run [drawFn] (the renderer's master-program quad
     * draw with areEffectsVisible=0), then unbind back to the default framebuffer.
     */
    fun drawGradedFrame(drawFn: () -> Unit) {
        if (!isReady()) return
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, fbo)
        GLES32.glViewport(0, 0, width, height)
        drawFn()
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
    }

    /**
     * Zero the SSBO, dispatch the compute kernel over ceil(size/16) work groups, barrier,
     * map the buffer read-only and copy the 192 uint32 counts into a fresh direct
     * LITTLE_ENDIAN ByteBuffer owned by the receiver. Returns null when disabled or the map
     * fails (renderer skips onHistogram that frame).
     */
    fun dispatchAndRead(): ByteBuffer? {
        if (!isReady()) return null

        // CPU-side zero each dispatch (kernel accumulates with atomicAdd).
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssbo)
        GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, SSBO_BYTES, ByteBuffer.allocateDirect(SSBO_BYTES))

        GLES32.glUseProgram(program)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, tex)
        if (uSrc >= 0) GLES32.glUniform1i(uSrc, 0)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, ssbo)

        val groupsX = (width + WORK_GROUP - 1) / WORK_GROUP
        val groupsY = (height + WORK_GROUP - 1) / WORK_GROUP
        GLES31.glDispatchCompute(groupsX, groupsY, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT or GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)

        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssbo)
        val mapped = GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, SSBO_BYTES, GLES31.GL_MAP_READ_BIT) as? ByteBuffer
        var result: ByteBuffer? = null
        if (mapped != null) {
            mapped.order(ByteOrder.LITTLE_ENDIAN)
            val outBuf = ByteBuffer.allocateDirect(SSBO_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val count = Models.HISTOGRAM_BINS * 3
            for (i in 0 until count) outBuf.putInt(mapped.int)
            outBuf.rewind()
            result = outBuf
        }
        GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
        return result
    }

    /** Drop FBO/texture/SSBO (and optionally the program). Safe to call repeatedly. */
    private fun releaseTargets(keepProgram: Boolean) {
        computeReady = false
        if (fbo != 0) {
            GLES32.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            fbo = 0
        }
        if (tex != 0) {
            GLES32.glDeleteTextures(1, intArrayOf(tex), 0)
            tex = 0
        }
        if (ssbo != 0) {
            GLES31.glDeleteBuffers(1, intArrayOf(ssbo), 0)
            ssbo = 0
        }
        if (!keepProgram && program != 0) {
            GLES32.glDeleteProgram(program)
            program = 0
            uSrc = -1
        }
    }

    /** Full teardown (GL thread). */
    fun release() {
        releaseTargets(keepProgram = false)
    }
}
