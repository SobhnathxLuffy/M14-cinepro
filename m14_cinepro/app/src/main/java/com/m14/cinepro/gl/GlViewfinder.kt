package com.m14.cinepro.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import com.m14.cinepro.core.EngineListener
import com.m14.cinepro.core.Models
import com.m14.cinepro.core.SettingsStore

/**
 * M14 CinePro — GL viewfinder surface (Architecture A: 1080p GL monitoring pipeline).
 *
 * Setup law: EGL context client version 3 (ES 3.2 device: Mali-G68 MP2), RGBA8888 +
 * 24-bit depth config chooser, EGL context preserved across pause, continuous render mode.
 * The camera frame arrives via the renderer-owned SurfaceTexture (GL_TEXTURE_EXTERNAL_OES);
 * [onTextureReady] is invoked exactly once per created SurfaceTexture from the GL thread so
 * MainActivity can hand it to CameraEngine (it must call SurfaceTexture.setDefaultBufferSize
 * to the viewfinder size before attaching the capture session).
 *
 * Wiring (MainActivity):
 *   viewfinder.renderer.eisMatrixProvider = { gyroEis.matrixIfEnabled() }        // null = EIS off
 *   viewfinder.renderer.wbGainsProvider  = { wbController.normalizedShaderGains() }
 *   viewfinder.applyLut(preset, importedPath)                               // LUT changes
 *   viewfinder.refreshLuminanceLut()                                        // rare re-bake
 */
class GlViewfinder(
    context: Context,
    settings: SettingsStore,
    listener: EngineListener,
    onTextureReady: (SurfaceTexture) -> Unit
) : GLSurfaceView(context) {

    /** 3D LUT engine (generators, .cube parser, luminance bake, half-float codec). */
    val lutEngine = LutEngine()

    /** The GLSurfaceView.Renderer; expose for provider wiring (eisMatrixProvider/wbGainsProvider). */
    val renderer: ViewfinderRenderer =
        ViewfinderRenderer(context, settings, listener, lutEngine, onTextureReady)

    init {
        renderer.host = this
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        setPreserveEGLContextOnPause(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /**
     * Apply a LUT preset (posts to the GL thread; resolved + uploaded at the next frame
     * boundary). For Models.LutPreset.IMPORTED pass the .cube file path — non-null paths are
     * stored on [LutEngine.importedPath] before the request is queued.
     */
    fun applyLut(preset: Models.LutPreset, importedPath: String?) {
        if (!importedPath.isNullOrEmpty()) lutEngine.importedPath = importedPath
        queueEvent { renderer.requestLut(preset) }
    }

    /** Re-bake + re-upload the 33^3 luminance L* 3D texture on the GL thread. */
    fun refreshLuminanceLut() {
        queueEvent { renderer.refreshLuminanceLut() }
    }

    /** Deterministic teardown: release GL objects + SurfaceTexture, then pause the render thread. */
    fun releaseViewfinder() {
        queueEvent { renderer.releaseGl() }
        onPause()
    }
}
