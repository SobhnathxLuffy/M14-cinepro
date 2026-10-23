package com.m14.cinepro.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import com.m14.cinepro.core.EngineListener
import com.m14.cinepro.core.SettingsStore
import java.nio.ByteBuffer

/**
 * M14 CinePro recording controller — synthetic-CFR HEVC recording per stage5_media.md §2-§3 and
 * M14_VERIFIED_TRUTH (c2.exynos.hevc.encoder verified at 80 Mbps 4K on the Galaxy M14 5G).
 *
 * Architecture (Architecture A record path):
 *  - The camera/GL pipeline renders into the persistent input surface returned by [prepareEncoder];
 *    the surface is created ONCE and reused across takes/resolutions (re-created only when the
 *    codec itself changes, because the persistent surface belongs to the codec instance).
 *  - Encoder candidates (fallback chain, first that configures+starts wins):
 *      c2.exynos.hevc.encoder -> OMX.Exynos.HEVC.Encoder -> default HEVC (MediaCodecList)
 *      -> c2.exynos.h264.encoder -> default AVC.
 *  - CFR LAW (stage5 §3): while recording, the first drained video buffer anchors firstPtsUs and
 *    EVERY recorded buffer is restamped to firstPtsUs + frameIndex * frameDurationUs with
 *    frameDurationUs = round(1e6 / fps); frameIndex advances only after a successful muxer write.
 *    When not recording, buffers are drained and released (render=false) — the encoder keeps
 *    running because the persistent surface is always fed.
 *  - MUXER ORDER LAW (stage5 §8.3): the video track is always added before the audio track. The
 *    muxer starts as soon as (video + audio formats) OR (video + 2 s audio timeout → video-only);
 *    audio samples arriving before muxer start are buffered and flushed after start.
 *  - Threading: ONE HandlerThread "EncoderDrain" owns all codec callbacks and muxer writes;
 *    startRecording/stopRecording post onto it for full serialization. All EngineListener calls
 *    are marshalled to the main looper.
 */
class RecordingController(
    private val context: Context,
    private val settings: SettingsStore,
    private val listener: EngineListener
) {

    companion object {
        private const val TAG = "M14Rec"

        /** MediaFormat "color-format" = 0x7F000789 COLOR_FormatSurface (stage5 §2.2). */
        private const val COLOR_FORMAT_SURFACE = 2130708361
        /** "color-standard" = BT709. */
        private const val COLOR_STANDARD_BT709 = 1
        /** "color-transfer" = SDR_VIDEO (SMPTE170M). */
        private const val COLOR_TRANSFER_SDR_VIDEO = 3
        /** "color-range" = limited. */
        private const val COLOR_RANGE_LIMITED = 2

        private const val MIME_HEVC = "video/hevc"
        private const val MIME_AVC = "video/avc"
        private const val HEVC_EXYNOS_C2 = "c2.exynos.hevc.encoder"
        private const val HEVC_OMX_EXYNOS = "OMX.Exynos.HEVC.Encoder"
        private const val AVC_EXYNOS_C2 = "c2.exynos.h264.encoder"

        private const val AUDIO_FORMAT_TIMEOUT_MS = 2000L
        private const val MUX_STOP_DELAY_MS = 300L
        private const val MAX_PENDING_AUDIO = 1024
        private const val FRAME_TICK_MS = 100L
    }

    private class Candidate(val name: String, val mime: String)

    val audioEngine = AudioEngine(settings, listener)
    val gyroLogger = GyroflowLogger(context)
    val slateManager = SlateManager(context, settings)

    private val storage = StorageHelper(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val drainThread = HandlerThread("EncoderDrain").also { it.start() }
    private val drainHandler = Handler(drainThread.looper)

    // ---- encoder state (created on caller thread; drained on EncoderDrain) ----
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var configuredKey = ""
    private var currentCodecName = "none"
    private var currentMime = MIME_HEVC
    private var videoFormat: MediaFormat? = null
    private var fps = 24
    private var frameDurationUs = 41667L

    // ---- take state (all mutations on the drain thread; volatile flags for cross-thread reads) ----
    @Volatile private var recording = false
    @Volatile private var frameIndexCounter = 0L
    private var firstPtsUs = -1L
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1
    private var audioFormat: MediaFormat? = null
    private var audioTimeoutNanos = 0L
    private val pendingAudio = ArrayList<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
    @Volatile private var muxWriteFailed = false
    private var clipBaseName = ""
    private var takeStartMs = 0L
    private var videoUri: Uri? = null
    private var videoPfd: ParcelFileDescriptor? = null

    init {
        gyroLogger.errorListener = { stage, t -> notifyError(stage, t) }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Creates (once) or reconfigures the video encoder. Called by CameraEngine BEFORE session
     * creation each time resolution/fps/bitrate changes. Returns the persistent input surface
     * the camera must render into (null when every codec candidate failed).
     */
    fun prepareEncoder(width: Int, height: Int, fps: Int, bitrate: Int, highSpeed: Boolean): Surface? {
        val key = "$width x $height @ ${fps}fps ${bitrate}bps hs=$highSpeed"
        val existing = codec
        val surface = inputSurface
        if (existing != null && surface != null && configuredKey == key) {
            return surface
        }
        if (recording) {
            Log.w(TAG, "prepareEncoder called while recording — reconfiguring encoder")
        }
        val candidates = encoderCandidates(width, height, fps, highSpeed)
        for (candidate in candidates) {
            var created: MediaCodec? = null
            var persistent: Surface? = null
            try {
                val fmt = buildVideoFormat(candidate.mime, width, height, fps, bitrate, highSpeed)
                created = MediaCodec.createByCodecName(candidate.name)
                persistent = MediaCodec.createPersistentInputSurface()
                // Tear down the previous codec + its persistent surface BEFORE the new codec
                // starts producing callbacks, then publish all controller state, so the drain
                // callbacks never observe a half-updated configuration.
                existingCodecRelease()
                codec = created
                inputSurface = persistent
                configuredKey = key
                currentCodecName = candidate.name
                currentMime = candidate.mime
                videoFormat = null
                this.fps = fps
                frameDurationUs = Math.round(1_000_000.0 / fps)
                if (!startCodec(created, fmt, persistent)) {
                    Log.w(TAG, "candidate ${candidate.name} unusable — falling back")
                    unwindCandidate(created, persistent)
                    continue
                }
                Log.i(
                    TAG,
                    "encoder ready: ${candidate.name} ($currentMime) ${width}x$height @${fps}fps " +
                        "${bitrate / 1_000_000} Mbps highSpeed=$highSpeed"
                )
                notifyInfo("ENC ${candidate.name} ${width}x$height@${fps}")
                return persistent
            } catch (e: Throwable) {
                Log.w(TAG, "create ${candidate.name} failed: ${e.message}")
                // Only unwind controller state if THIS candidate was wired in; an earlier failure
                // (e.g. unknown codec name) must leave a still-running previous encoder intact.
                val c = created
                val s = persistent
                if (c != null && s != null && codec === c) {
                    unwindCandidate(c, s)
                }
            }
        }
        notifyError(
            "encoder",
            RuntimeException("no video encoder available for ${width}x$height @ ${fps}fps " +
                "(tried ${candidates.size} candidates)")
        )
        return inputSurface
    }

    /**
     * Opens the muxer (StorageHelper-created .mp4, rotation 0), forces an IDR via the
     * "request-sync" vendor parameter, starts audio + gyro telemetry, writes the slate JSON,
     * then flips into the CFR restamp/write state and notifies onRecordingStarted.
     */
    fun startRecording(clipBaseName: String) {
        drainHandler.post { startRecordingLocked(clipBaseName) }
    }

    /**
     * Leaves the CFR state, sends "drop-input-frames", and finalizes (muxer stop+release,
     * audio/gyro teardown, publish) 300 ms later on the drain thread.
     */
    fun stopRecording() {
        drainHandler.post { stopRecordingLocked() }
    }

    /** Full codec release; the persistent input surface is released with it. */
    fun releaseEncoder() {
        drainHandler.post { releaseEncoderLocked() }
    }

    fun isRecording(): Boolean = recording

    fun frameIndex(): Long = frameIndexCounter

    // ------------------------------------------------------------------
    // Encoder setup
    // ------------------------------------------------------------------

    private fun encoderCandidates(width: Int, height: Int, fps: Int, highSpeed: Boolean): List<Candidate> {
        val list = ArrayList<Candidate>(5)
        list.add(Candidate(HEVC_EXYNOS_C2, MIME_HEVC))
        list.add(Candidate(HEVC_OMX_EXYNOS, MIME_HEVC))
        defaultEncoderFor(MIME_HEVC, width, height, fps, highSpeed)?.let { list.add(Candidate(it, MIME_HEVC)) }
        list.add(Candidate(AVC_EXYNOS_C2, MIME_AVC))
        defaultEncoderFor(MIME_AVC, width, height, fps, highSpeed)?.let { list.add(Candidate(it, MIME_AVC)) }
        return list.distinctBy { it.name }
    }

    private fun defaultEncoderFor(mime: String, width: Int, height: Int, fps: Int, highSpeed: Boolean): String? = try {
        // findEncoderForFormat may mutate the format — query with a throwaway instance.
        val probe = buildVideoFormat(mime, width, height, fps, settings.bitrate.bps, highSpeed)
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(probe)
    } catch (e: Throwable) {
        Log.w(TAG, "default encoder query failed for $mime", e)
        null
    }

    /** MediaFormat exactly per stage5 §2.2/§2.3 (+ PART 2 task spec); no profile key for HEVC Main. */
    private fun buildVideoFormat(
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        highSpeed: Boolean
    ): MediaFormat {
        val fmt = MediaFormat.createVideoFormat(mime, width, height)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT_SURFACE)
        fmt.setInteger("color-standard", COLOR_STANDARD_BT709)
        fmt.setInteger("color-transfer", COLOR_TRANSFER_SDR_VIDEO)
        fmt.setInteger("color-range", COLOR_RANGE_LIMITED)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        fmt.setInteger(MediaFormat.KEY_MAX_WIDTH, width)
        fmt.setInteger(MediaFormat.KEY_MAX_HEIGHT, height)
        fmt.setInteger(MediaFormat.KEY_STRIDE, width)
        fmt.setInteger(MediaFormat.KEY_SLICE_HEIGHT, height)
        fmt.setInteger(MediaFormat.KEY_PRIORITY, 0)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0)
        fmt.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 1.0f)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        if (highSpeed) {
            // 720p60 high-speed session: HAL outputs 60.06 fps real-time (M14_VERIFIED_TRUTH §4C),
            // so capture-rate == fps keeps playback real-time.
            fmt.setFloat("capture-rate", fps.toFloat())
        }
        return fmt
    }

    /**
     * configure + setInputSurface + setCallback + start. Framework versions differ on whether
     * setCallback is accepted before configure — both legal orderings are attempted with
     * MediaCodec.reset() between them.
     */
    private fun startCodec(created: MediaCodec, fmt: MediaFormat, surface: Surface): Boolean {
        try {
            created.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.setInputSurface(surface)
            created.setCallback(codecCallback, drainHandler)
            created.start()
            return true
        } catch (first: Throwable) {
            Log.w(TAG, "configure-first path failed (${first.message}); retrying callback-first")
        }
        try {
            created.reset()
        } catch (e: Throwable) {
            Log.w(TAG, "codec reset failed", e)
            return false
        }
        try {
            created.setCallback(codecCallback, drainHandler)
            created.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.setInputSurface(surface)
            created.start()
            return true
        } catch (second: Throwable) {
            Log.w(TAG, "codec start failed on both paths", second)
            return false
        }
    }

    private fun existingCodecRelease() {
        codec?.let { old ->
            try { old.stop() } catch (e: Throwable) { Log.w(TAG, "old codec stop", e) }
            try { old.release() } catch (e: Throwable) { Log.w(TAG, "old codec release", e) }
        }
        inputSurface?.let { old ->
            try { old.release() } catch (e: Throwable) { Log.w(TAG, "old surface release", e) }
        }
    }

    /** Releases a rejected candidate and clears controller state that pointed at it. */
    private fun unwindCandidate(created: MediaCodec, persistent: Surface) {
        try { created.release() } catch (e: Exception) { Log.w(TAG, "release failed", e) }
        try { persistent.release() } catch (e: Exception) { Log.w(TAG, "surface release failed", e) }
        if (codec === created) codec = null
        if (inputSurface === persistent) inputSurface = null
        configuredKey = ""
    }

    private fun releaseEncoderLocked() {
        codec?.let { c ->
            try { c.stop() } catch (e: Throwable) { Log.w(TAG, "codec stop", e) }
            try { c.release() } catch (e: Throwable) { Log.w(TAG, "codec release", e) }
        }
        codec = null
        inputSurface?.let { s ->
            try { s.release() } catch (e: Throwable) { Log.w(TAG, "surface release", e) }
        }
        inputSurface = null
        configuredKey = ""
        videoFormat = null
        Log.i(TAG, "encoder released (persistent surface freed)")
    }

    // ------------------------------------------------------------------
    // Codec drain (EncoderDrain thread)
    // ------------------------------------------------------------------

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            // Surface input — buffers are rendered by the camera/GL pipeline, nothing to queue.
        }

        override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            drainVideoOutput(mc, index, info)
        }

        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "video output format changed ($currentCodecName)")
            videoFormat = format
            onVideoFormatAvailable()
        }

        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "encoder error: ${e.diagnosticInfo}", e)
            notifyError("encoder", e)
            if (recording) stopRecording() // safe-stop; posts onto the drain thread
        }
    }

    /** CFR restamp + muxer write (stage5 §3). Runs on the EncoderDrain thread. */
    private fun drainVideoOutput(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val out = try {
            mc.getOutputBuffer(index)
        } catch (e: Throwable) {
            null
        }
        if (out != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            if (recording) {
                if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                info.presentationTimeUs = firstPtsUs + frameIndexCounter * frameDurationUs
                val mux = muxer
                if (mux != null && muxerStarted && videoTrack >= 0 && !muxWriteFailed) {
                    try {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        mux.writeSampleData(videoTrack, out, info)
                        frameIndexCounter++ // advance only after a successful write
                    } catch (e: Throwable) {
                        Log.e(TAG, "video mux write failed", e)
                        muxWriteFailed = true
                        notifyError("muxer", e)
                    }
                }
            }
        }
        try {
            mc.releaseOutputBuffer(index, false)
        } catch (e: Throwable) {
            Log.w(TAG, "releaseOutputBuffer failed", e)
        }
    }

    /** Adds the video track (always before audio — stage5 §8.3 order law) and maybe starts the muxer. */
    private fun onVideoFormatAvailable() {
        val mux = muxer ?: return
        if (muxerStarted) return
        val fmt = videoFormat ?: return
        if (videoTrack < 0) {
            videoTrack = try {
                mux.addTrack(fmt)
            } catch (e: Throwable) {
                Log.e(TAG, "video addTrack failed", e)
                notifyError("muxer", e)
                -1
            }
            if (videoTrack >= 0) {
                val af = audioFormat
                if (af != null && audioTrack < 0) {
                    audioTrack = try {
                        mux.addTrack(af)
                    } catch (e: Throwable) {
                        Log.e(TAG, "audio addTrack failed", e)
                        notifyError("muxer", e)
                        -1
                    }
                }
            }
        }
        tryStartMuxer()
    }

    /** Audio track add (called via drain-handler post from the AudioEngine format callback). */
    private fun onAudioFormatAvailable(format: MediaFormat) {
        audioFormat = format
        val mux = muxer ?: return
        if (muxerStarted) {
            Log.w(TAG, "audio format arrived after muxer start — take stays video-only")
            return
        }
        if (videoTrack >= 0 && audioTrack < 0) {
            audioTrack = try {
                mux.addTrack(format)
            } catch (e: Throwable) {
                Log.e(TAG, "audio addTrack failed", e)
                notifyError("muxer", e)
                -1
            }
        }
        tryStartMuxer()
    }

    private fun tryStartMuxer() {
        if (muxerStarted || muxer == null) return
        if (videoTrack < 0) return
        val audioReady = audioTrack >= 0
        val timedOut = System.nanoTime() >= audioTimeoutNanos
        if (!audioReady && !timedOut) return
        try {
            muxer?.start()
            muxerStarted = true
            val msg = if (audioReady) "MUX video+audio" else "MUX video-only (no audio format in 2 s)"
            Log.i(TAG, msg)
            notifyInfo(msg)
            flushPendingAudio()
        } catch (e: Throwable) {
            Log.e(TAG, "muxer start failed", e)
            notifyError("muxer", e)
        }
    }

    private fun flushPendingAudio() {
        val mux = muxer ?: return
        if (!muxerStarted || audioTrack < 0) {
            pendingAudio.clear()
            return
        }
        for ((buffer, info) in pendingAudio) {
            if (muxWriteFailed) break
            try {
                mux.writeSampleData(audioTrack, buffer, info)
            } catch (e: Throwable) {
                Log.e(TAG, "buffered audio write failed", e)
                muxWriteFailed = true
                notifyError("muxer", e)
                break
            }
        }
        pendingAudio.clear()
    }

    // ------------------------------------------------------------------
    // Audio sink (encoded AAC -> muxer, serialized on the drain thread)
    // ------------------------------------------------------------------

    private val audioSink = object : AudioEngine.AudioSink {
        override fun onAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            drainHandler.post {
                if (!recording) return@post
                val mux = muxer
                if (mux != null && muxerStarted && audioTrack >= 0 && !muxWriteFailed) {
                    try {
                        mux.writeSampleData(audioTrack, buffer, info)
                    } catch (e: Throwable) {
                        Log.e(TAG, "audio mux write failed", e)
                        muxWriteFailed = true
                        notifyError("muxer", e)
                    }
                } else if (mux != null) {
                    // Buffer until the muxer starts (audio-before-video / muxer-start latency).
                    if (pendingAudio.size >= MAX_PENDING_AUDIO) pendingAudio.removeAt(0)
                    pendingAudio.add(buffer to info)
                }
            }
        }
    }

    init {
        // Wired here (after audioSink declaration) to avoid a forward reference from the first init block.
        audioEngine.setSink(audioSink)
    }

    // ------------------------------------------------------------------
    // Take lifecycle (drain thread)
    // ------------------------------------------------------------------

    private fun startRecordingLocked(clip: String) {
        if (recording) {
            Log.w(TAG, "startRecording ignored — already recording")
            return
        }
        if (codec == null || inputSurface == null) {
            notifyError("record", RuntimeException("encoder not prepared — call prepareEncoder() first"))
            return
        }
        try {
            clipBaseName = clip
            firstPtsUs = -1L
            frameIndexCounter = 0L
            muxWriteFailed = false
            takeStartMs = System.currentTimeMillis()

            val fd = storage.videoFd(clip)
            if (fd == null) {
                notifyError("storage", RuntimeException("cannot create video file for $clip (storage unavailable?)"))
                return
            }
            videoUri = fd.first
            videoPfd = fd.second
            // Rotation 0: the sensor is used in its native landscape orientation.
            muxer = MediaMuxer(fd.second.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoTrack = -1
            audioTrack = -1
            audioFormat = null
            muxerStarted = false
            pendingAudio.clear()

            // Force IDR at record start (vendor key, stage5 §3 — mk7.f() equivalent).
            try {
                val sync = Bundle()
                sync.putInt("request-sync", 0)
                codec?.setParameters(sync)
            } catch (e: Throwable) {
                Log.w(TAG, "request-sync unsupported by ${currentCodecName}: ${e.message}")
            }

            // Audio: format arrives via callback -> drain thread -> addTrack after video track.
            audioTimeoutNanos = System.nanoTime() + AUDIO_FORMAT_TIMEOUT_MS * 1_000_000L
            audioEngine.start { format -> drainHandler.post { onAudioFormatAvailable(format) } }

            // Gyroflow telemetry: <clip>.gcsv at 500 Hz (Movies + DCIM copies).
            try {
                gyroLogger.start(
                    clip,
                    "R${settings.reel} S${settings.scene} T${settings.take} ${settings.describeForMetadata()}"
                )
            } catch (e: Throwable) {
                Log.e(TAG, "gyro logger failed to start", e)
                notifyError("gyro", e)
            }

            // Slate registry: <clip>_slate.json (Movies + DCIM copies).
            try {
                slateManager.writeSlateJson(clip, storage)
            } catch (e: Throwable) {
                Log.e(TAG, "slate write failed", e)
                notifyError("slate", e)
            }

            // The encoder may have already reported its output format (it keeps running between
            // takes) — add the video track right now so the muxer can start on first audio sample.
            if (videoFormat != null) onVideoFormatAvailable()
            drainHandler.postDelayed({ tryStartMuxer() }, AUDIO_FORMAT_TIMEOUT_MS)

            recording = true
            drainHandler.postDelayed(frameTicker, FRAME_TICK_MS)
            Log.i(TAG, "REC start: $clip")
            mainHandler.post { listener.onRecordingStarted(clip) }
        } catch (e: Throwable) {
            Log.e(TAG, "startRecording failed", e)
            notifyError("record", e)
            cleanupFailedStartLocked()
        }
    }

    private fun stopRecordingLocked() {
        if (!recording) {
            Log.w(TAG, "stopRecording ignored — not recording")
            return
        }
        recording = false
        drainHandler.removeCallbacks(frameTicker)
        try {
            val drop = Bundle()
            drop.putInt("drop-input-frames", 1)
            codec?.setParameters(drop)
        } catch (e: Throwable) {
            Log.w(TAG, "drop-input-frames unsupported by ${currentCodecName}: ${e.message}")
        }
        Log.i(TAG, "REC stop: $clipBaseName — finalizing in ${MUX_STOP_DELAY_MS} ms")
        drainHandler.postDelayed({ finalizeTakeLocked() }, MUX_STOP_DELAY_MS)
    }

    private fun finalizeTakeLocked() {
        val clip = clipBaseName
        val frames = frameIndexCounter
        val durationMs = System.currentTimeMillis() - takeStartMs
        val started = muxerStarted // captured before teardown flips the flag
        if (started) {
            try {
                muxer?.stop()
            } catch (e: Throwable) {
                Log.e(TAG, "muxer stop failed", e)
                notifyError("muxer", e)
            }
        } else {
            Log.w(TAG, "muxer never started — discarding unfinished clip $clip")
        }
        try { muxer?.release() } catch (e: Throwable) { Log.w(TAG, "muxer release", e) }
        muxer = null
        muxerStarted = false
        videoTrack = -1
        audioTrack = -1
        pendingAudio.clear()
        try { videoPfd?.close() } catch (e: Throwable) { Log.w(TAG, "pfd close", e) }
        videoPfd = null
        if (videoUri != null) {
            if (started) {
                storage.publish(videoUri!!)
            } else {
                storage.deletePending(videoUri!!)
            }
        }
        videoUri = null

        try { audioEngine.stop() } catch (e: Throwable) { Log.w(TAG, "audio stop", e) }
        try { gyroLogger.stop() } catch (e: Throwable) { Log.w(TAG, "gyro stop", e) }
        slateManager.nextTake() // roll the take counter for the next clip

        Log.i(TAG, "take finalized: $clip frames=$frames durationMs=$durationMs")
        mainHandler.post { listener.onRecordingStopped(clip, durationMs, frames) }
    }

    private fun cleanupFailedStartLocked() {
        recording = false
        drainHandler.removeCallbacks(frameTicker)
        try { muxer?.release() } catch (e: Throwable) { Log.w(TAG, "muxer release", e) }
        muxer = null
        muxerStarted = false
        videoTrack = -1
        audioTrack = -1
        pendingAudio.clear()
        try { videoPfd?.close() } catch (e: Throwable) { Log.w(TAG, "pfd close", e) }
        videoPfd = null
        videoUri?.let { storage.deletePending(it) }
        videoUri = null
        try { audioEngine.stop() } catch (e: Throwable) { Log.w(TAG, "audio stop", e) }
        try { gyroLogger.stop() } catch (e: Throwable) { Log.w(TAG, "gyro stop", e) }
    }

    // ------------------------------------------------------------------
    // Timecode ticker (EngineListener.onFrameCount ~10 Hz)
    // ------------------------------------------------------------------

    private val frameTicker = object : Runnable {
        override fun run() {
            if (!recording) return
            val frames = frameIndexCounter
            val rate = fps
            mainHandler.post { listener.onFrameCount(frames, rate) }
            drainHandler.postDelayed(this, FRAME_TICK_MS)
        }
    }

    // ------------------------------------------------------------------
    // Listener plumbing (always via the main looper)
    // ------------------------------------------------------------------

    private fun notifyError(stage: String, t: Throwable) {
        mainHandler.post { listener.onError(stage, t) }
    }

    private fun notifyInfo(message: String) {
        mainHandler.post { listener.onInfo(message) }
    }
}
