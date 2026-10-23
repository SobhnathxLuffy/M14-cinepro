package com.m14.cinepro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.m14.cinepro.camera.CameraEngine
import com.m14.cinepro.core.EngineListener
import com.m14.cinepro.core.Models
import com.m14.cinepro.core.SettingsStore
import com.m14.cinepro.gl.GlViewfinder
import com.m14.cinepro.hud.HudController
import com.m14.cinepro.hud.SlateDialog
import com.m14.cinepro.recording.RecordingController
import com.m14.cinepro.recording.StorageHelper
import com.m14.cinepro.sensor.GyroEisController
import com.m14.cinepro.system.SystemMonitor
import java.util.Locale

/**
 * M14 CinePro — MainActivity. Landscape cinema camera wiring hub.
 *
 * Owns: permissions gate, engine/subsystem lifetimes, the [HudController] hook decisions,
 * session (re)builds, the 10 Hz timecode tick and LUT import (framework SAF, zero androidx).
 *
 * Public API for other modules: none — everything is driven through HudController.Hooks.
 */
class MainActivity : Activity(), HudController.Hooks {

    companion object {
        private const val TAG = "M14Hud"
        private const val REQ_PERMISSIONS = 1001
        private const val REQ_LUT_IMPORT = 1002
        private const val TICK_MS = 100L
        private const val BACK_EXIT_WINDOW_MS = 2000L
    }

    private lateinit var settings: SettingsStore
    private var listener: EngineListener? = null
    private var hud: HudController? = null
    private var glView: GlViewfinder? = null
    private var engine: CameraEngine? = null
    private var recController: RecordingController? = null
    private var gyroEis: GyroEisController? = null
    private var systemMonitor: SystemMonitor? = null
    private var storage: StorageHelper? = null

    @Volatile private var pendingPreviewTexture: SurfaceTexture? = null
    @Volatile private var sessionReady = false
    private var engineInitialized = false
    private var lastBackPressAt = 0L

    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    // -----------------------------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        applyImmersive()

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            initEngine()
        } else {
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        glView?.onResume()
    }

    override fun onPause() {
        // Graceful stop: never leave the encoder running while the activity is backgrounded.
        if (recController?.isRecording() == true) {
            runCatching { recController?.stopRecording() }
                .onFailure { Log.w(TAG, "stopRecording on pause failed", it) }
            hud?.setRecording(false, false)
        }
        glView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        tickRunnable?.let { tickHandler.removeCallbacks(it) }
        tickRunnable = null
        systemMonitor?.stop()
        systemMonitor = null
        runCatching { engine?.close() }
        engine = null
        runCatching { recController?.releaseEncoder() }
        runCatching { recController?.audioEngine?.stopMeteringOnly() }
        recController = null
        gyroEis?.setEnabled(false)
        gyroEis = null
        hud?.destroy()
        hud = null
        sessionReady = false
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (recController?.isRecording() == true) {
            toast("Stop recording first")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastBackPressAt < BACK_EXIT_WINDOW_MS) {
            finish()
        } else {
            lastBackPressAt = now
            toast("Press again to exit")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    // -----------------------------------------------------------------------------------------
    // Immersive sticky landscape
    // -----------------------------------------------------------------------------------------

    private fun applyImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            val ic = try { window.insetsController } catch (_: Throwable) { null }
            ic?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    // -----------------------------------------------------------------------------------------
    // Permissions
    // -----------------------------------------------------------------------------------------

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            initEngine()
        } else {
            toast("Camera and microphone permissions are required")
            Log.e(TAG, "Permissions denied — finishing")
            finish()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Engine bring-up
    // -----------------------------------------------------------------------------------------

    private fun initEngine() {
        if (engineInitialized) return
        engineInitialized = true

        settings = SettingsStore(this)
        listener = buildEngineListener()
        val engineListener = listener!!

        storage = StorageHelper(this)
        systemMonitor = SystemMonitor(this, engineListener).also { it.start() }
        recController = RecordingController(this, settings, engineListener)
        gyroEis = GyroEisController(this)

        hud = HudController(this, settings, this).also { h ->
            h.lutImportRequester = { requestLutImport() }
            h.lutApplier = { preset, path -> glView?.applyLut(preset, path) }
            h.onSettingsDirty = { engine?.applySettings() }
        }

        val root = findViewById<FrameLayout>(R.id.hud_root)
        glView = GlViewfinder(this, settings, engineListener) { st ->
            pendingPreviewTexture = st
            maybeStartCamera()
        }.also { gv ->
            root.addView(gv, 0)
        }
        glView?.renderer?.eisMatrixProvider = {
            if (settings.eisOn) gyroEis?.matrix() else null
        }
        glView?.renderer?.wbGainsProvider = {
            engine?.whiteBalanceController?.normalizedShaderGains() ?: floatArrayOf(1f, 1f, 1f)
        }

        // Metering-only audio after permissions (spec: monitoring starts without recording).
        runCatching { recController?.audioEngine?.startMeteringOnly() }
            .onFailure { Log.w(TAG, "audio metering-only start failed", it) }

        hud?.refreshAll()
        startTick()
        Log.i(TAG, "Engine initialized; waiting for preview texture + session")
    }

    private fun maybeStartCamera() {
        if (engine != null) return
        if (pendingPreviewTexture == null) return
        val permissionsOk = arrayOf(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
        ).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (!permissionsOk) {
            Log.i(TAG, "Preview texture ready but permissions missing; waiting")
            return
        }

        engine = CameraEngine(this, settings, listener!!).also { e ->
            e.open {
                rebuildSession()
            }
        }
    }

    private fun rebuildSession() {
        val e = engine ?: return
        val tex = pendingPreviewTexture ?: run {
            Log.w(TAG, "rebuildSession: no preview texture yet")
            return
        }
        if (recController?.isRecording() == true) {
            runCatching { recController?.stopRecording() }
                .onFailure { Log.w(TAG, "stopRecording before rebuild failed", it) }
            hud?.setRecording(false, false)
        }
        sessionReady = false
        val p = settings.resolution
        // ALWAYS prepare the record surface up front (record-ready pipeline, Architecture A).
        val recordSurface = recController?.prepareEncoder(
            p.width, p.height, p.fps, settings.bitrate.bps, p.highSpeed
        ) ?: run {
            hud?.setStatus("ENCODER PREP FAILED")
            return
        }
        e.createSession(recordSurface, tex) { ok ->
            sessionReady = ok
            if (ok) {
                e.applySettings()
                hud?.setStatus("Ready")
            } else {
                hud?.setStatus("SESSION FAILED")
                Log.e(TAG, "Camera session configuration failed")
            }
        }
    }

    private fun startTick() {
        val r = object : Runnable {
            override fun run() {
                hud?.updateTimecode(recController?.frameIndex() ?: 0L, settings.fps)
                tickHandler.postDelayed(this, TICK_MS)
            }
        }
        tickRunnable = r
        tickHandler.post(r)
    }

    // -----------------------------------------------------------------------------------------
    // EngineListener forwards
    // -----------------------------------------------------------------------------------------

    private fun buildEngineListener(): EngineListener = object : EngineListener {
        override fun onRecordingStarted(clipBaseName: String) {
            hud?.setRecording(true, true)
            hud?.setStatus("REC $clipBaseName")
        }

        override fun onRecordingStopped(clipBaseName: String, durationMs: Long, frameCount: Long) {
            hud?.setRecording(false, false)
            hud?.setStatus("Saved $clipBaseName")
            hud?.refreshAll() // take auto-bump happened in RecordingController
            toast(
                String.format(
                    Locale.US, "SAVED %s  %.1fs  %d fr",
                    clipBaseName, durationMs / 1000f, frameCount
                )
            )
        }

        override fun onError(stage: String, throwable: Throwable) {
            Log.e(TAG, "Engine error @ $stage", throwable)
            if (recController?.isRecording() == true) {
                runCatching { recController?.stopRecording() }
                hud?.setRecording(false, false)
            }
            hud?.setStatus("ERR: ${stage.uppercase(Locale.US)}")
            toast(getString(R.string.err_camera) + " ($stage)")
        }

        override fun onInfo(message: String) {
            hud?.setStatus(message)
        }

        override fun onFrameCount(frameIndex: Long, fps: Int) {
            hud?.updateTimecode(frameIndex, fps)
        }

        override fun onAudioLevels(peakLeftDb: Float, rmsLeftDb: Float, peakRightDb: Float, rmsRightDb: Float) {
            hud?.onLevels(peakLeftDb, rmsLeftDb, peakRightDb, rmsRightDb)
        }

        override fun onHistogram(bins: java.nio.ByteBuffer) {
            hud?.onHistogram(bins)
        }

        override fun onCameraTelemetry(aeState: Int, afState: Int, sceneFlicker: Int) {
            settings.sceneFlicker = sceneFlicker
            hud?.onTelemetry(aeState, afState, sceneFlicker)
        }

        override fun onSystemStatus(batteryPct: Int, tempC: Float, thermalHeadroom: Float, freeGb: Float) {
            hud?.onSystemStatus(batteryPct, tempC, thermalHeadroom, freeGb)
        }
    }

    // -----------------------------------------------------------------------------------------
    // HudController.Hooks — transport decisions
    // -----------------------------------------------------------------------------------------

    override fun onRecordToggle() {
        val rec = recController ?: return
        if (rec.isRecording()) {
            runCatching { rec.stopRecording() }
                .onFailure { Log.e(TAG, "stopRecording failed", it) }
            hud?.setRecording(false, false)
        } else {
            if (!sessionReady || engine == null) {
                hud?.setStatus("NO SESSION")
                toast("Camera session not ready")
                return
            }
            val base = runCatching { rec.slateManager.clipBaseName() }
                .getOrDefault("M14_A001_C001")
            runCatching { rec.startRecording(base) }
                .onFailure {
                    Log.e(TAG, "startRecording failed", it)
                    hud?.setStatus("REC FAILED")
                    toast(getString(R.string.err_camera) + " (record)")
                    return
                }
            hud?.setRecording(true, true)
        }
    }

    override fun onSlateOpen() {
        SlateDialog.show(this, settings) { hud?.refreshAll() }
    }

    override fun onEisToggled(on: Boolean) {
        gyroEis?.setEnabled(on)
        hud?.setStatus(if (on) "EIS ON" else "EIS OFF")
    }

    override fun onTorchToggled(on: Boolean) {
        val e = engine
        if (e == null) {
            hud?.setStatus("TORCH (NO CAM)")
            return
        }
        runCatching { e.setTorch(on) }
            .onFailure { Log.w(TAG, "setTorch failed", it) }
    }

    override fun onFormatChanged(preset: Models.ResPreset) {
        Log.i(TAG, "Format -> $preset; rebuilding session")
        rebuildSession()
    }

    override fun onBitrateChanged(p: Models.BitratePreset) {
        Log.i(TAG, "Bitrate -> $p; rebuilding session (encoder reconfig)")
        rebuildSession()
    }

    override fun onFocusTap(nx: Float, ny: Float) {
        val e = engine ?: return
        runCatching { e.focusAt(nx, ny) }
            .onFailure { Log.w(TAG, "focusAt failed", it) }
    }

    override fun onMarkerTap(index: Int) {
        val marker = settings.focusMarkers.getOrNull(index)
        if (marker == null) {
            toast("F${index + 1} not set — long-press to set")
            return
        }
        engine?.focusAt(marker[0], marker[1])
        hud?.showTapBoxAt(marker[0], marker[1])
    }

    override fun onMarkerLongPress(index: Int) {
        val pt = hud?.lastTapPoint()
        if (pt == null) {
            toast("Tap the viewfinder first")
            return
        }
        settings.saveMarker(index, pt[0], pt[1])
        hud?.refreshMarkers()
        toast("F${index + 1} SET")
    }

    // -----------------------------------------------------------------------------------------
    // LUT import (framework SAF — zero androidx)
    // -----------------------------------------------------------------------------------------

    private fun requestLutImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/octet-stream"))
        }
        runCatching { startActivityForResult(intent, REQ_LUT_IMPORT) }
            .onFailure {
                Log.w(TAG, "No document picker available", it)
                toast(getString(R.string.err_lut_import))
            }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_LUT_IMPORT) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            toast(getString(R.string.err_lut_import))
            return
        }
        runCatching {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Persistable grant not offered by the provider; the immediate read still works.
            }
            storage?.importCubeLut(uri)
        }.onSuccess { path ->
            if (path != null) {
                glView?.applyLut(Models.LutPreset.IMPORTED, path)
                hud?.refreshAfterLut(path)
                toast("LUT imported")
                Log.i(TAG, "LUT imported: $path")
            } else {
                toast(getString(R.string.err_lut_import))
            }
        }.onFailure {
            Log.e(TAG, "LUT import failed", it)
            toast(getString(R.string.err_lut_import))
        }
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private fun toast(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
