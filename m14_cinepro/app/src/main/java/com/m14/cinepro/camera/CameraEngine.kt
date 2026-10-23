package com.m14.cinepro.camera

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import com.m14.cinepro.core.EngineListener
import com.m14.cinepro.core.Models
import com.m14.cinepro.core.SettingsStore
import java.util.concurrent.Executor

private const val TAG = "M14Cam"

/** Camera 0 = back camera (task law; M14 single rear 50MP S5KJN1). */
private const val CAMERA_ID = "0"

/** CameraEngine handler-thread priority (foreground-ish, per task law setThreadPriority(tid, -2)). */
private const val CAM_THREAD_PRIORITY = -2

/** Camera telemetry to the HUD is throttled to >= 3 Hz (333 ms floor). */
private const val TELEMETRY_MIN_INTERVAL_MS = 333L

/** "frame drop (HAL)" info lines are throttled to at most one per second. */
private const val FRAME_DROP_INFO_INTERVAL_MS = 1_000L

/** Delay before the post-tap repeating request flips CONTROL_AF_TRIGGER START -> IDLE (~150 ms law). */
private const val AF_TRIGGER_IDLE_RESET_DELAY_MS = 160L

/** HFR (720p high-speed) target fps — verified output of the M14 high-speed pipeline. */
private const val HFR_FPS = 60

/** Raised for CameraDevice.StateCallback.onError codes (no dedicated platform exception type). */
private class CameraDeviceException(message: String) : RuntimeException(message)

/** Raised for CameraCaptureSession.StateCallback.onConfigureFailed. */
private class SessionException(message: String) : RuntimeException(message)

/**
 * Sanitized request-key law (M14): setting a key the platform does not accept must throw
 * IllegalArgumentException at most into this guard — never up into request construction.
 */
fun <T> CaptureRequest.Builder.trySet(key: CaptureRequest.Key<T>, value: T) {
    try {
        set(key, value)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "request key rejected by platform: ${key.name}", e)
    }
}

/**
 * M14 CinePro — Camera2 orchestrator.
 *
 * Owns the camera device, the capture session (regular or constrained high-speed) and the single
 * repeating request that carries the manual/auto exposure, WB and AF state applied by
 * [ExposureController], [WhiteBalanceController] and [FocusController].
 *
 * Key laws implemented here (stage3_camera2.md + M14_VERIFIED_TRUTH.md):
 *  - camera id "0" opened on a dedicated HandlerThread "CameraEngine" (priority -2);
 *  - regular sessions via SessionConfiguration(SESSION_REGULAR), HFR (settings.resolution.highSpeed)
 *    via createConstrainedHighSpeedCaptureSession + createHighSpeedRequestList with
 *    CONTROL_AE_TARGET_FPS_RANGE from the §2.4 e(fps) selection law (upper == 60, prefer lower == 60);
 *  - base request template TEMPLATE_RECORD (TEMPLATE_PREVIEW for HFR, required by CONSTRAINED_HIGH_SPEED),
 *    CONTROL_CAPTURE_INTENT = VIDEO_RECORD (non-HFR only), CONTROL_MODE_AUTO,
 *    CONTROL_AE_TARGET_FPS_RANGE = Range(fps, fps), NOISE_REDUCTION_MODE_FAST (via FocusController);
 *    CONTROL_ENABLE_ZSL is never set;
 *  - capture-callback telemetry (AE/AF state, STATISTICS_SCENE_FLICKER) to [EngineListener] at >= 3 Hz,
 *    frame drops surfaced as throttled info lines — never a crash;
 *  - one automatic session rebuild on session failure.
 *
 * All listener callbacks are delivered on the main thread.
 */
class CameraEngine(
    private val context: Context,
    private val settings: SettingsStore,
    private val listener: EngineListener
) {

    /** Exposure controller — public for direct HUD reads (caps/labels). */
    val exposureController = ExposureController(settings)

    /** Focus controller — public for direct HUD use (tap state queries / explicit restore). */
    val focusController = FocusController(settings)

    /** White-balance controller — public for direct HUD use (normalizedShaderGains for the GL trim). */
    val whiteBalanceController = WhiteBalanceController(settings)

    /** Snapshot of the exposure-relevant HAL capabilities for the HUD. */
    data class CapsInfo(
        val exposureMinNs: Long,
        val exposureMaxNs: Long,
        val isoMin: Int,
        val isoMax: Int
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Session-configuration executor: dispatches session callbacks onto the camera thread. */
    private val sessionExecutor = Executor { runnable ->
        val handler = camHandler
        if (handler != null) handler.post(runnable) else runnable.run()
    }

    /** Post-tap delayed refresh: flips CONTROL_AF_TRIGGER back to IDLE ~150 ms after a tap. */
    private val tapIdleReset = Runnable { applySettingsInternal() }

    @Volatile private var handlerThread: HandlerThread? = null
    @Volatile private var camHandler: Handler? = null

    @Volatile private var cameraManager: CameraManager? = null
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var sessionConfigured = false
    @Volatile private var deviceWanted = false

    @Volatile private var sensorOrientationDeg = 90
    @Volatile private var activeArrayRect: Rect? = null
    @Volatile private var exposureCaps: ExposureController.CameraCaps? = null
    @Volatile private var highSpeedFpsRanges: Array<Range<Int>>? = null

    private var currentRecordSurface: Surface? = null
    private var currentPreviewSurface: Surface? = null
    private var currentPreviewTexture: SurfaceTexture? = null
    private var pendingOnReady: (() -> Unit)? = null
    private var sessionRebuildAttempted = false

    private var lastTelemetryAtMs = 0L
    private var lastFrameDropInfoAtMs = 0L

    // -----------------------------------------------------------------------------------------
    // Public API (MainActivity wiring)
    // -----------------------------------------------------------------------------------------

    /**
     * Open camera id "0" (back), cache characteristics/caps, then invoke [onReady] on the main
     * thread. Failures are reported through [EngineListener.onError] ("open"/"device"/"open-cam")
     * and leave [onReady] uncalled. Idempotent: calling open while already open re-invokes onReady.
     */
    fun open(onReady: () -> Unit) {
        val handler = ensureHandler()
        handler.post {
            deviceWanted = true
            if (device != null) {
                cacheCharacteristics()
                mainNotify { onReady() }
                return@post
            }
            pendingOnReady = onReady
            try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cameraManager = manager
                manager.openCamera(CAMERA_ID, deviceCallback, handler)
            } catch (e: SecurityException) {
                failOpen(e)
            } catch (e: IllegalArgumentException) {
                failOpen(e)
            } catch (e: CameraAccessException) {
                failOpen(e)
            } catch (e: Exception) {
                failOpen(e)
            }
        }
    }

    /**
     * Tear down the capture session and close the camera device. Idempotent; safe to call from
     * any thread. The handler thread stays alive so the engine can be re-opened after close().
     */
    fun close() {
        deviceWanted = false
        pendingOnReady = null
        val thread = handlerThread ?: return
        if (!thread.isAlive) return
        val handler = camHandler ?: return
        handler.post {
            session?.let { s ->
                try { s.stopRepeating() } catch (t: Throwable) { Log.w(TAG, "stopRepeating failed", t) }
                try { s.abortCaptures() } catch (t: Throwable) { Log.w(TAG, "abortCaptures failed", t) }
                try { s.close() } catch (t: Throwable) { Log.w(TAG, "session close failed", t) }
            }
            session = null
            sessionConfigured = false
            device?.let { d ->
                try { d.close() } catch (t: Throwable) { Log.w(TAG, "device close failed", t) }
            }
            device = null
        }
    }

    /**
     * Build the capture session for [settings.resolution].
     *
     * Outputs: [recordSurface] (encoder input; may be null for preview-only operation) plus the
     * viewfinder surface created from [previewTexture]. The preview buffer is set to
     * 1920x1080 in regular sessions and to the resolution's own size (1280x720) in the
     * high-speed session — the M14 constrained-high-speed pipeline accepts exactly one surface
     * size, `[1280x720]`, and attaching a 1920x1080 preview surface throws
     * `IllegalArgumentException: Surface size 1920x1080 is not part of the high speed supported
     * size list` (V1_BUG_LOG). CP3 law: BOTH the preview buffer AND the MediaCodec surface are
     * 1280x720 in the 720p 60 fps mode. Regular resolutions use SessionConfiguration(SESSION_REGULAR);
     * high-speed resolutions use createConstrainedHighSpeedCaptureSession and a
     * createHighSpeedRequestList repeating burst.
     *
     * [onConfigured] is invoked exactly once on the main thread with the outcome; on failure one
     * automatic rebuild is attempted before reporting false.
     */
    fun createSession(recordSurface: Surface?, previewTexture: SurfaceTexture, onConfigured: (Boolean) -> Unit) {
        val handler = ensureHandler()
        handler.post {
            currentRecordSurface = recordSurface
            currentPreviewTexture = previewTexture
            sessionRebuildAttempted = false
            try {
                val p = settings.resolution
                if (p.highSpeed) {
                    // CP3 law: the HS HAL only accepts [1280x720] — the preview buffer must match
                    // the encoder size exactly or the session is rejected.
                    previewTexture.setDefaultBufferSize(p.width, p.height)
                } else {
                    previewTexture.setDefaultBufferSize(Models.VIEWFINDER_WIDTH, Models.VIEWFINDER_HEIGHT)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "setDefaultBufferSize failed", t)
            }
            closeSessionQuietly()
            val previewSurface = Surface(previewTexture)
            currentPreviewSurface = previewSurface
            createSessionOutputs(recordSurface, previewSurface, onConfigured)
        }
    }

    /**
     * Rebuild and re-push the repeating request with the current settings state (exposure, WB,
     * focus, torch, fps). No-op when no session is configured.
     */
    fun applySettings() {
        val handler = ensureHandler()
        handler.post { applySettingsInternal() }
    }

    /**
     * Tap-to-focus at viewfinder-normalized coordinates ([nx], [ny] in the upright, display-
     * rotated viewfinder, y down). Arms the FocusController two-phase AF sequence and refreshes
     * the repeating request; a delayed refresh (~150 ms) resets the AF trigger to IDLE.
     */
    fun focusAt(nx: Float, ny: Float) {
        val handler = ensureHandler()
        handler.post {
            val currentSession = session ?: return@post
            val activeArray = activeArrayRect ?: return@post
            val builder = buildBaseRequest() ?: return@post
            focusController.startTapFocus(FocusController.FocusPoint(nx, ny), activeArray, sensorOrientationDeg, builder)
            setRepeating(currentSession, builder)
            handler.removeCallbacks(tapIdleReset)
            handler.postDelayed(tapIdleReset, AF_TRIGGER_IDLE_RESET_DELAY_MS)
        }
    }

    /**
     * Torch toggle. Preferred path is request-level FLASH_MODE_TORCH inside the session
     * (stage3 §2.9 x()), so this just records the state and re-applies settings; the value is
     * persisted and also honored by the next session build.
     */
    fun setTorch(on: Boolean) {
        settings.torchOn = on
        val handler = ensureHandler()
        handler.post { applySettingsInternal() }
    }

    /** True while a configured capture session with a record output is live. */
    fun isRecordingSessionActive(): Boolean = sessionConfigured && currentRecordSurface != null

    /** Exposure/ISO capability snapshot for the HUD, or null before the camera is opened. */
    fun capsSnapshot(): CapsInfo? {
        val caps = exposureCaps ?: return null
        val exposureRange = caps.exposureRange
        val isoRange = caps.isoRange
        return CapsInfo(
            exposureMinNs = exposureRange?.lower ?: Models.EXPOSURE_MIN_NS,
            exposureMaxNs = exposureRange?.upper ?: Models.EXPOSURE_MAX_NS,
            isoMin = isoRange?.lower ?: Models.ISO_MIN,
            isoMax = isoRange?.upper ?: Models.ISO_MAX
        )
    }

    /** Sensor mount orientation in degrees (0/90/180/270); 90 until characteristics are read. */
    fun sensorOrientation(): Int = sensorOrientationDeg

    /** SENSOR_INFO_ACTIVE_ARRAY_SIZE, or null before the camera is opened. */
    fun activeArray(): Rect? = activeArrayRect

    // -----------------------------------------------------------------------------------------
    // Camera device lifecycle
    // -----------------------------------------------------------------------------------------

    private fun failOpen(e: Exception) {
        pendingOnReady = null
        Log.w(TAG, "openCamera failed", e)
        mainNotify { listener.onError("open", e) }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (!deviceWanted) {
                // close() raced the HAL — discard the unwanted handle.
                try { camera.close() } catch (t: Throwable) { Log.w(TAG, "unwanted device close failed", t) }
                return
            }
            device = camera
            cacheCharacteristics()
            val ready = pendingOnReady
            pendingOnReady = null
            if (ready != null) mainNotify { ready() }
        }

        override fun onDisconnected(camera: CameraDevice) {
            try { camera.close() } catch (t: Throwable) { Log.w(TAG, "device close failed", t) }
            device = null
            sessionConfigured = false
            mainNotify { listener.onInfo("camera disconnected") }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            try { camera.close() } catch (t: Throwable) { Log.w(TAG, "device close failed", t) }
            device = null
            sessionConfigured = false
            mainNotify { listener.onError("device", CameraDeviceException("CameraDevice error $error")) }
        }
    }

    private fun cacheCharacteristics() {
        try {
            val manager = cameraManager
                ?: (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).also { cameraManager = it }
            val characteristics = manager.getCameraCharacteristics(CAMERA_ID)
            exposureCaps = exposureController.readCaps(characteristics)
            sensorOrientationDeg = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            activeArrayRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            highSpeedFpsRanges = try {
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.highSpeedVideoFpsRanges
            } catch (t: Throwable) {
                null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "characteristics unavailable", e)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Session lifecycle
    // -----------------------------------------------------------------------------------------

    private fun closeSessionQuietly() {
        val current = session ?: return
        try { current.stopRepeating() } catch (t: Throwable) { Log.w(TAG, "stopRepeating failed", t) }
        try { current.close() } catch (t: Throwable) { Log.w(TAG, "session close failed", t) }
        if (session === current) session = null
        sessionConfigured = false
    }

    @Suppress("DEPRECATION")
    private fun createSessionOutputs(recordSurface: Surface?, previewSurface: Surface, onConfigured: (Boolean) -> Unit) {
        val cameraDevice = device
        if (cameraDevice == null) {
            Log.w(TAG, "createSession without an open device")
            mainNotify { onConfigured(false) }
            return
        }
        val highSpeed = settings.resolution.highSpeed

        val outputs = ArrayList<Surface>(2)
        if (recordSurface != null) outputs.add(recordSurface)
        outputs.add(previewSurface)

        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                session = configured
                val builder = buildBaseRequest()
                val ok = builder != null && setRepeating(configured, builder)
                if (ok) sessionRebuildAttempted = false
                sessionConfigured = ok
                mainNotify { onConfigured(ok) }
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) {
                Log.w(TAG, "session configuration failed")
                if (session === configured) session = null
                sessionConfigured = false
                mainNotify { onConfigured(false) }
                handleSessionFailure(SessionException("session configuration failed"))
            }

            override fun onClosed(closed: CameraCaptureSession) {
                if (session === closed) {
                    session = null
                    sessionConfigured = false
                }
            }
        }

        try {
            if (highSpeed) {
                cameraDevice.createConstrainedHighSpeedCaptureSession(outputs, stateCallback, camHandler)
            } else {
                val outputConfigs = outputs.map { OutputConfiguration(it) }
                val sessionConfiguration = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    sessionExecutor,
                    stateCallback
                )
                cameraDevice.createCaptureSession(sessionConfiguration)
            }
        } catch (e: CameraAccessException) {
            failSessionCreate(e, onConfigured)
        } catch (e: IllegalArgumentException) {
            failSessionCreate(e, onConfigured)
        } catch (e: SecurityException) {
            failSessionCreate(e, onConfigured)
        } catch (e: Exception) {
            failSessionCreate(e, onConfigured)
        }
    }

    private fun failSessionCreate(e: Exception, onConfigured: (Boolean) -> Unit) {
        Log.w(TAG, "createSession failed", e)
        mainNotify { listener.onError("session", e) }
        mainNotify { onConfigured(false) }
    }

    /** One-shot session rebuild law: on session failure, rebuild exactly once. */
    private fun handleSessionFailure(e: Throwable) {
        Log.w(TAG, "session failure", e)
        sessionConfigured = false
        mainNotify { listener.onError("session", e) }
        if (!sessionRebuildAttempted) {
            sessionRebuildAttempted = true
            val previewSurface = currentPreviewSurface
            if (previewSurface != null) {
                createSessionOutputs(currentRecordSurface, previewSurface) { ok ->
                    if (!ok) Log.w(TAG, "session rebuild failed")
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Repeating request
    // -----------------------------------------------------------------------------------------

    /**
     * Build the base repeating request: template + targets + static keys + the three controllers.
     * Null when the device or the mandatory preview surface is gone.
     */
    private fun buildBaseRequest(): CaptureRequest.Builder? {
        val cameraDevice = device ?: return null
        val previewSurface = currentPreviewSurface ?: return null
        val recordSurface = currentRecordSurface
        val highSpeed = settings.resolution.highSpeed

        val template = if (highSpeed) CameraDevice.TEMPLATE_PREVIEW else CameraDevice.TEMPLATE_RECORD
        val builder = try {
            cameraDevice.createCaptureRequest(template)
        } catch (e: Throwable) {
            Log.w(TAG, "createCaptureRequest failed", e)
            null
        } ?: return null

        try {
            builder.addTarget(previewSurface)
        } catch (t: Throwable) {
            Log.w(TAG, "preview target rejected", t)
        }
        if (recordSurface != null) {
            try {
                builder.addTarget(recordSurface)
            } catch (t: Throwable) {
                Log.w(TAG, "record target rejected", t)
            }
        }

        if (highSpeed) {
            // HFR repeating burst: §2.4 e(fps) selection (upper == 60, prefer lower == 60).
            builder.trySet(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, hfrFpsRange())
        } else {
            builder.trySet(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
            val fps = settings.fps
            if (fps > 0) builder.trySet(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        }
        builder.trySet(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        // CONTROL_ENABLE_ZSL is never set (M14 law: deterministic non-ZSL video pipeline).

        exposureController.apply(builder, exposureCaps, settings.fps)
        whiteBalanceController.apply(builder, whiteBalanceController.currentCcm())
        focusController.apply(builder, activeArrayRect, sensorOrientationDeg)

        builder.trySet(
            CaptureRequest.FLASH_MODE,
            if (settings.torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
        return builder
    }

    /**
     * Push [builder] as the repeating request (regular) or as the high-speed burst list (HFR).
     * Returns false and routes through the one-rebuild law on session-level failures; a rejected
     * request payload is logged + surfaced as info without a rebuild loop.
     */
    private fun setRepeating(targetSession: CameraCaptureSession, builder: CaptureRequest.Builder): Boolean {
        val handler = camHandler
        return try {
            if (targetSession is CameraConstrainedHighSpeedCaptureSession) {
                val list = targetSession.createHighSpeedRequestList(builder.build())
                targetSession.setRepeatingBurst(list, captureCallback, handler)
            } else {
                targetSession.setRepeatingRequest(builder.build(), captureCallback, handler)
            }
            true
        } catch (e: CameraAccessException) {
            handleSessionFailure(e)
            false
        } catch (e: IllegalStateException) {
            handleSessionFailure(e)
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "repeating request rejected", e)
            mainNotify { listener.onInfo("request rejected: ${e.message ?: "bad request"}") }
            false
        }
    }

    private fun applySettingsInternal() {
        val currentSession = session ?: return
        val builder = buildBaseRequest() ?: return
        setRepeating(currentSession, builder)
    }

    /** stage3 §2.4 e(fps): pick the high-speed range with upper == HFR_FPS, prefer lower == HFR_FPS. */
    private fun hfrFpsRange(): Range<Int> {
        val ranges = highSpeedFpsRanges
        if (ranges != null) {
            var minLower = -1
            var bestWithMinLower: Range<Int>? = null
            for (range in ranges) {
                if (range.upper != HFR_FPS) continue
                if (range.lower == HFR_FPS) return range
                if (minLower < 0 || range.lower < minLower) {
                    minLower = range.lower
                    bestWithMinLower = range
                }
            }
            if (bestWithMinLower != null) return bestWithMinLower
        }
        return Range(HFR_FPS, HFR_FPS)
    }

    // -----------------------------------------------------------------------------------------
    // Capture callback: telemetry / AF drive / CCM cache / frame drops
    // -----------------------------------------------------------------------------------------

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            try {
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE) ?: 0
                val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: 0
                val flicker = result.get(CaptureResult.STATISTICS_SCENE_FLICKER) ?: 0

                val afWantsRefresh = focusController.onAfResult(afState)
                settings.sceneFlicker = flicker
                whiteBalanceController.onCaptureResult(result)
                if (afWantsRefresh) applySettingsInternal()

                val now = SystemClock.elapsedRealtime()
                if (now - lastTelemetryAtMs >= TELEMETRY_MIN_INTERVAL_MS) {
                    lastTelemetryAtMs = now
                    mainNotify { listener.onCameraTelemetry(aeState, afState, flicker) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "capture telemetry failed", t)
            }
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameDropInfoAtMs >= FRAME_DROP_INFO_INTERVAL_MS) {
                    lastFrameDropInfoAtMs = now
                    mainNotify { listener.onInfo("frame drop (HAL)") }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "frame-drop notify failed", t)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Threading
    // -----------------------------------------------------------------------------------------

    /** Handler thread lifecycle: (re)created lazily so open() works after close(). */
    private fun ensureHandler(): Handler {
        var thread = handlerThread
        if (thread == null || !thread.isAlive) {
            thread = HandlerThread("CameraEngine")
            thread.start()
            handlerThread = thread
            val handler = Handler(thread.looper)
            camHandler = handler
            // Priority is set from INSIDE the thread: Thread.threadId is still -1 before the
            // thread has run, and setThreadPriority(-1, ...) throws "Given thread -1 does not
            // exist" (V1_BUG_LOG, every open). First runnable = deterministic tid.
            handler.post {
                try {
                    Process.setThreadPriority(Process.myTid(), CAM_THREAD_PRIORITY)
                } catch (t: Throwable) {
                    Log.w(TAG, "thread priority set failed", t)
                }
            }
        } else if (camHandler == null) {
            camHandler = Handler(thread.looper)
        }
        return camHandler!!
    }

    /** All listener deliveries go to the main thread. */
    private fun mainNotify(block: () -> Unit) {
        mainHandler.post(block)
    }
}
