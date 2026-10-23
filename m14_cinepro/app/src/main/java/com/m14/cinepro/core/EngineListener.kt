package com.m14.cinepro.core

import java.nio.ByteBuffer

/**
 * Callback surface from the engine subsystems up to the HUD / MainActivity.
 * All methods are invoked on main-thread-safe paths unless noted otherwise.
 */
interface EngineListener {

    /** Recording started. [clipBaseName] is the file base name (no extension) of this take. */
    fun onRecordingStarted(clipBaseName: String)

    /** Recording stopped; [durationMs] is wall-clock take length, [frameCount] encoded frames. */
    fun onRecordingStopped(clipBaseName: String, durationMs: Long, frameCount: Long)

    /** Hard engine failure. The HUD shows a toast; recording is stopped safely if active. */
    fun onError(stage: String, throwable: Throwable)

    /** Transient info line (e.g. "AF locked", "LUT imported", "50 Hz flicker detected"). */
    fun onInfo(message: String)

    /** Per-frame encoded frame counter for the timecode pill (called ~10 Hz from engine). */
    fun onFrameCount(frameIndex: Long, fps: Int)

    /** Stereo audio levels in dBFS, one call per audio buffer (~93 Hz at 512-frame reads). */
    fun onAudioLevels(peakLeftDb: Float, rmsLeftDb: Float, peakRightDb: Float, rmsRightDb: Float)

    /**
     * 64-bin RGB histogram from the GLES 3.1 compute pass at 10 fps.
     * Buffer holds 192 little-endian uint32 counts: R=[0,64) G=[64,128) B=[128,192).
     * The buffer is a fresh copy owned by the receiver.
     */
    fun onHistogram(bins: ByteBuffer)

    /** Camera2 CaptureResult telemetry (called at >= 3 Hz). */
    fun onCameraTelemetry(aeState: Int, afState: Int, sceneFlicker: Int)

    /** Battery %, SoC/battery temperature in °C, thermal headroom, free GB on the record volume. */
    fun onSystemStatus(batteryPct: Int, tempC: Float, thermalHeadroom: Float, freeGb: Float)
}
