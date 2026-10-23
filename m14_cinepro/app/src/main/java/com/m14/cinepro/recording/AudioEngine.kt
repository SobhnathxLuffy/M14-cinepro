package com.m14.cinepro.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import com.m14.cinepro.core.EngineListener
import com.m14.cinepro.core.Models
import com.m14.cinepro.core.SettingsStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * M14 CinePro audio subsystem (stage5_media.md §5 + PART 3 spec).
 *
 * Capture : AudioRecord 48 kHz stereo 16-bit, source fallback UNPROCESSED(9) -> CAMCORDER(5) -> MIC(1),
 *           buffer = max(minBufferSize, 8192) bytes, 512-frame blocking reads (~93 Hz) on the
 *           "AudioCapture" thread at THREAD_PRIORITY_URGENT_AUDIO.
 * Encode  : MediaCodec "audio/mp4a-latm", AAC-LC (aac-profile=2), 256 kbps, pcm-encoding=16-bit,
 *           priority 0, async mode on the "AudioEncode" HandlerThread; input PCM is queued with
 *           AudioTimestamp(TIMEBASE_MONOTONIC).nanoTime/1000 PTS (System.nanoTime()/1000 fallback)
 *           and MediaCodec carries the input PTS through to the output buffers.
 * Muxing  : encoded samples are handed to the [AudioSink] (RecordingController) — the controller
 *           owns all MediaMuxer writes on its single "EncoderDrain" thread.
 * Meters  : per buffer per channel peak = max|sample|; rolling RMS over the last 50 ms (2400 frames);
 *           dBFS = 20*log10(linear) - Models.DBFS_REF_16BIT (90.308998), floored at -60;
 *           peak hold decays 0.5 dB after 48 idle buffers (~0.5 s); levels emitted at most every 50 ms.
 *
 * Live preview metering without recording: [startMeteringOnly] / [stopMeteringOnly] run the same
 * capture thread in meter-only mode; both paths are mutually exclusive with [start].
 */
class AudioEngine(
    private val settings: SettingsStore,
    private val listener: EngineListener
) {

    /** Consumer of encoded AAC samples. The ByteBuffer is a fresh direct buffer owned by the sink. */
    interface AudioSink {
        fun onAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
    }

    companion object {
        private const val TAG = "M14Audio"
        private const val MIME_AAC = "audio/mp4a-latm"
        private const val AAC_OBJECT_LC = 2
        private const val READ_FRAMES = 512
        private const val MIN_BUFFER_BYTES = 8192
        private const val RMS_WINDOW_FRAMES = 2400 // 50 ms @ 48 kHz
        private const val HOLD_RELEASE_FRAMES = 48 // ~0.5 s of buffers
        private const val HOLD_DECAY_DB = 0.5f
        private const val METER_FLOOR_DB = -60f
        private const val EMIT_INTERVAL_NS = 50_000_000L
        private const val MAX_PCM_QUEUE = 128
        private const val MAX_BAD_READS = 500
        private const val START_POLL_MS = 50L
        private const val START_TIMEOUT_MS = 1500L
        private const val RETRY_FEED_MS = 10L
    }

    private enum class Mode { IDLE, METER_ONLY, RECORDING }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var sink: AudioSink? = null

    // ---- capture state ----
    @Volatile private var recorder: AudioRecord? = null
    private var recorderSource = 0
    @Volatile private var captureThread: Thread? = null
    private val running = AtomicBoolean(false)

    // ---- encoder state ----
    private var codec: MediaCodec? = null
    private var encodeThread: HandlerThread? = null
    private var encodeHandler: Handler? = null

    // ---- queueing ----
    private class Chunk(val pcm: ShortArray, val ptsUs: Long)
    private val pcmQueue = ConcurrentLinkedQueue<Chunk>()
    private val freeInputs = ConcurrentLinkedQueue<Int>()

    // ---- mode ----
    @Volatile private var mode: Mode = Mode.IDLE
    private var formatReady: ((MediaFormat) -> Unit)? = null

    // ---- meter state (owned by AudioCapture thread; snapshots @Volatile for emitters) ----
    private val windowL = FloatArray(RMS_WINDOW_FRAMES)
    private val windowR = FloatArray(RMS_WINDOW_FRAMES)
    private var winIdx = 0
    private var winCount = 0
    private var sumSqL = 0.0
    private var sumSqR = 0.0
    private val heldPeak = floatArrayOf(METER_FLOOR_DB, METER_FLOOR_DB)
    private val holdFrames = intArrayOf(0, 0)
    @Volatile private var outPeakL = METER_FLOOR_DB
    @Volatile private var outRmsL = METER_FLOOR_DB
    @Volatile private var outPeakR = METER_FLOOR_DB
    @Volatile private var outRmsR = METER_FLOOR_DB
    @Volatile private var lastEmitNanos = 0L
    @Volatile private var metersLive = false

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Wires the encoded-sample consumer. Call before [start]. */
    fun setSink(sink: AudioSink?) {
        this.sink = sink
    }

    /**
     * Starts (or re-targets) the capture+encode pipeline for a recording take.
     * [onFormatReady] receives the encoder output MediaFormat (with csd-0) exactly once,
     * from the AudioEncode thread — RecordingController adds the muxer audio track from it.
     */
    @Synchronized
    fun start(onFormatReady: (MediaFormat) -> Unit) {
        formatReady = onFormatReady
        if (mode == Mode.RECORDING) {
            Log.w(TAG, "start ignored — already recording")
            return
        }
        if (!ensureCaptureRunning()) return
        if (encodeThread == null) {
            val t = HandlerThread("AudioEncode")
            t.start()
            encodeThread = t
            encodeHandler = Handler(t.looper)
        }
        pcmQueue.clear()
        freeInputs.clear()
        if (!openEncoder()) {
            // No AAC encoder: capture keeps running for meters; RecordingController muxes video-only.
            Log.e(TAG, "AAC encoder unavailable — take will be video-only")
            return
        }
        mode = Mode.RECORDING
        Log.i(TAG, "audio recording started (48 kHz stereo, AAC-LC ${Models.AUDIO_BITRATE / 1000} kbps)")
    }

    /** Stops capture + encoder. Idempotent; safe from any thread. */
    @Synchronized
    fun stop() {
        if (mode == Mode.IDLE && !running.get()) return
        mode = Mode.IDLE
        running.set(false)
        captureThread?.let { t ->
            try { t.join(1500) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
        captureThread = null
        releaseRecorder()
        teardownEncoder()
        pcmQueue.clear()
        freeInputs.clear()
        resetMeterState()
        metersLive = false
        formatReady = null
        Log.i(TAG, "audio engine stopped")
    }

    /** Live viewfinder meters without recording (no encoder). Mutually exclusive with [start]. */
    @Synchronized
    fun startMeteringOnly() {
        if (mode == Mode.RECORDING) {
            Log.w(TAG, "startMeteringOnly ignored — recording active")
            return
        }
        if (mode == Mode.METER_ONLY) return
        if (!ensureCaptureRunning()) return
        mode = Mode.METER_ONLY
        Log.i(TAG, "live metering started (mic source $recorderSource)")
    }

    /** Stops meter-only capture. No-op while a recording owns the pipeline. */
    @Synchronized
    fun stopMeteringOnly() {
        if (mode == Mode.RECORDING) return
        if (mode == Mode.IDLE && !running.get()) return
        mode = Mode.IDLE
        running.set(false)
        captureThread?.let { t ->
            try { t.join(1500) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
        captureThread = null
        releaseRecorder()
        resetMeterState()
        metersLive = false
        Log.i(TAG, "live metering stopped")
    }

    /** True once the capture thread has read at least one valid buffer. */
    fun metersActive(): Boolean = metersLive

    // ------------------------------------------------------------------
    // Capture
    // ------------------------------------------------------------------

    private fun ensureCaptureRunning(): Boolean {
        if (running.get()) return true
        if (!openRecorder()) {
            notifyError(RuntimeException("microphone unavailable (UNPROCESSED/CAMCORDER/MIC all failed)"))
            return false
        }
        running.set(true)
        val t = Thread({ captureLoop() }, "AudioCapture")
        captureThread = t
        t.start()
        return true
    }

    /** AudioRecord with stage5 §5.1 source fallback chain: 9 -> 5 -> 1. */
    @SuppressLint("MissingPermission")
    private fun openRecorder(): Boolean {
        val minBuf = try {
            AudioRecord.getMinBufferSize(
                Models.AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (e: Exception) {
            Log.e(TAG, "getMinBufferSize failed", e)
            0
        }
        if (minBuf <= 0) {
            Log.e(TAG, "invalid min buffer size: $minBuf")
            return false
        }
        val bufferSize = maxOf(minBuf, MIN_BUFFER_BYTES)
        val sources = intArrayOf(
            9, // MediaRecorder.AudioSource.UNPROCESSED
            5, // MediaRecorder.AudioSource.CAMCORDER
            1  // MediaRecorder.AudioSource.MIC
        )
        for (src in sources) {
            try {
                val rec = AudioRecord(
                    src,
                    Models.AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "source $src not initialized (state=${rec.state})")
                    try { rec.release() } catch (e: Exception) { Log.w(TAG, "release failed", e) }
                    continue
                }
                rec.startRecording()
                var waited = 0
                while (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING && waited < START_TIMEOUT_MS) {
                    try { Thread.sleep(START_POLL_MS) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
                    waited += START_POLL_MS.toInt()
                }
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.w(TAG, "source $src failed to reach RECORDING state")
                    try { rec.release() } catch (e: Exception) { Log.w(TAG, "release failed", e) }
                    continue
                }
                recorder = rec
                recorderSource = src
                Log.i(TAG, "AudioRecord ready: source=$src rate=${Models.AUDIO_SAMPLE_RATE} stereo 16-bit buffer=$bufferSize")
                return true
            } catch (e: SecurityException) {
                Log.w(TAG, "source $src rejected (RECORD_AUDIO permission?)", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "source $src invalid parameters", e)
            } catch (e: Exception) {
                Log.w(TAG, "source $src failed", e)
            }
        }
        return false
    }

    private fun releaseRecorder() {
        recorder?.let { rec ->
            try { rec.stop() } catch (e: Exception) { Log.w(TAG, "recorder stop", e) }
            try { rec.release() } catch (e: Exception) { Log.w(TAG, "recorder release", e) }
        }
        recorder = null
    }

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(READ_FRAMES * 2)
        var badReads = 0
        while (running.get()) {
            val rec = recorder ?: break
            val n = try {
                rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord.read threw", e)
                -1
            }
            if (n <= 0) {
                badReads++
                if (badReads % 100 == 1) Log.w(TAG, "audio read returned $n (consecutive=$badReads)")
                if (badReads >= MAX_BAD_READS) {
                    notifyError(RuntimeException("AudioRecord read failing persistently (last n=$n)"))
                    break
                }
                try { Thread.sleep(10) } catch (e: InterruptedException) { break }
                continue
            }
            badReads = 0
            metersLive = true
            val frames = n / 2
            if (frames > 0) {
                computeMeters(buf, frames)
                maybeEmitLevels()
                if (mode == Mode.RECORDING) {
                    val pts = currentPtsUs()
                    pcmQueue.offer(Chunk(buf.copyOf(frames * 2), pts))
                    if (pcmQueue.size > MAX_PCM_QUEUE) pcmQueue.poll()
                    encodeHandler?.post { drainInputs() }
                }
            }
        }
        Log.i(TAG, "AudioCapture thread exiting")
    }

    /** PTS in µs from the monotonic clock (stage5 §5.3: AudioTimestamp.nanoTime/1000, fallback nanoTime/1000). */
    private fun currentPtsUs(): Long {
        val rec = recorder ?: return System.nanoTime() / 1000
        val ts = AudioTimestamp()
        return try {
            if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                ts.nanoTime / 1000
            } else {
                System.nanoTime() / 1000
            }
        } catch (e: Exception) {
            System.nanoTime() / 1000
        }
    }

    // ------------------------------------------------------------------
    // Encoder
    // ------------------------------------------------------------------

    private fun buildAacFormat(): MediaFormat {
        val fmt = MediaFormat()
        fmt.setString(MediaFormat.KEY_MIME, MIME_AAC)
        fmt.setInteger(MediaFormat.KEY_SAMPLE_RATE, Models.AUDIO_SAMPLE_RATE)
        fmt.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, Models.AUDIO_BITRATE)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_OBJECT_LC)
        fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        fmt.setInteger(MediaFormat.KEY_PRIORITY, 0)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MIN_BUFFER_BYTES)
        return fmt
    }

    private fun openEncoder(): Boolean {
        val probe = buildAacFormat()
        val found = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(probe)
        } catch (e: Exception) {
            Log.w(TAG, "encoder query failed", e)
            null
        }
        val candidates = listOfNotNull(found, "c2.android.aac.encoder")
            .filter { it.isNotBlank() }
            .distinct()
        for (name in candidates) {
            val fmt = buildAacFormat()
            try {
                val c = MediaCodec.createByCodecName(name)
                if (!startEncoder(c, fmt)) {
                    try { c.release() } catch (e: Exception) { Log.w(TAG, "release failed", e) }
                    Log.w(TAG, "encoder $name unusable")
                    continue
                }
                codec = c
                Log.i(TAG, "AAC encoder: $name")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "create $name failed: ${e.message}")
            }
        }
        return false
    }

    /**
     * configure + setCallback + start. Both legal orderings are attempted (framework versions
     * differ on whether setCallback is accepted before configure); MediaCodec.reset() rewinds
     * between attempts.
     */
    private fun startEncoder(c: MediaCodec, fmt: MediaFormat): Boolean {
        val handler = encodeHandler ?: return false
        try {
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.setCallback(codecCallback, handler)
            c.start()
            return true
        } catch (first: Throwable) {
            Log.w(TAG, "configure-first path failed (${first.message}); retrying callback-first")
        }
        try {
            c.reset()
        } catch (e: Throwable) {
            Log.w(TAG, "codec reset failed", e)
            return false
        }
        try {
            c.setCallback(codecCallback, handler)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            return true
        } catch (second: Throwable) {
            Log.w(TAG, "encoder start failed on both paths", second)
            return false
        }
    }

    private fun teardownEncoder() {
        val et = encodeThread
        val eh = encodeHandler
        if (et != null && eh != null) {
            val released = CountDownLatch(1)
            eh.post {
                try {
                    codec?.let { c ->
                        try { c.stop() } catch (e: Exception) { Log.w(TAG, "codec stop", e) }
                        try { c.release() } catch (e: Exception) { Log.w(TAG, "codec release", e) }
                    }
                } finally {
                    codec = null
                    released.countDown()
                    et.quitSafely()
                }
            }
            try {
                released.await(2, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        encodeThread = null
        encodeHandler = null
    }

    /** Feeds queued PCM chunks into available async input buffers. Runs on the AudioEncode thread only. */
    private fun drainInputs() {
        val mc = codec ?: return
        while (true) {
            val index = freeInputs.poll() ?: break
            val chunk = pcmQueue.poll()
            if (chunk == null) {
                freeInputs.add(index)
                break
            }
            try {
                val bb = mc.getInputBuffer(index)
                if (bb != null) {
                    bb.clear()
                    bb.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(chunk.pcm, 0, chunk.pcm.size)
                    mc.queueInputBuffer(index, 0, chunk.pcm.size * 2, chunk.ptsUs, 0)
                } else {
                    Log.w(TAG, "getInputBuffer($index) returned null")
                }
            } catch (e: Exception) {
                Log.w(TAG, "queueInputBuffer failed", e)
            }
        }
        if (mode == Mode.RECORDING && freeInputs.isNotEmpty() && pcmQueue.isEmpty()) {
            // Input buffers waiting on the next capture chunk — retry shortly (safety net;
            // the capture thread also posts drainInputs after every offer).
            encodeHandler?.postDelayed({ if (mode == Mode.RECORDING) drainInputs() }, RETRY_FEED_MS)
        }
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            freeInputs.add(index)
            drainInputs()
        }

        override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    val consumer = sink
                    if (consumer != null) {
                        val out = mc.getOutputBuffer(index)
                        if (out != null) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            // Fresh direct copy: the sink writes it asynchronously on the muxer
                            // thread, while this codec buffer is released immediately.
                            val copy = ByteBuffer.allocateDirect(info.size)
                            copy.put(out)
                            copy.flip()
                            val ci = MediaCodec.BufferInfo()
                            ci.set(0, info.size, info.presentationTimeUs, info.flags)
                            consumer.onAudioSample(copy, ci)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "audio output handling failed", e)
            } finally {
                try { mc.releaseOutputBuffer(index, false) } catch (e: Exception) { Log.w(TAG, "release out buf", e) }
            }
        }

        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
            val cb = formatReady
            if (cb != null) {
                try {
                    cb(format)
                } catch (e: Throwable) {
                    Log.w(TAG, "format callback failed", e)
                }
            }
        }

        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "AAC codec error: ${e.diagnosticInfo}", e)
            notifyError(e)
        }
    }

    // ------------------------------------------------------------------
    // Metering (stage5 §5.6 law, 16-bit full-scale reference)
    // ------------------------------------------------------------------

    /** Clears meter accumulators (circular RMS windows, peak-hold) between sessions. */
    private fun resetMeterState() {
        windowL.fill(0f)
        windowR.fill(0f)
        winIdx = 0
        winCount = 0
        sumSqL = 0.0
        sumSqR = 0.0
        heldPeak[0] = METER_FLOOR_DB
        heldPeak[1] = METER_FLOOR_DB
        holdFrames[0] = 0
        holdFrames[1] = 0
        outPeakL = METER_FLOOR_DB
        outRmsL = METER_FLOOR_DB
        outPeakR = METER_FLOOR_DB
        outRmsR = METER_FLOOR_DB
    }

    /** dBFS relative to 16-bit full scale: 20*log10(x) - 90.308998, clamped to [-60, 0]. */
    private fun dbfs(linear: Float): Float {
        if (linear <= 0f) return METER_FLOOR_DB
        val db = (Math.log10(linear.toDouble()) * 20.0).toFloat() - Models.DBFS_REF_16BIT
        return db.coerceIn(METER_FLOOR_DB, 0f)
    }

    private fun computeMeters(buf: ShortArray, frames: Int) {
        var peakL = 0f
        var peakR = 0f
        for (i in 0 until frames) {
            val l = Math.abs(buf[i * 2].toFloat())
            val r = Math.abs(buf[i * 2 + 1].toFloat())
            if (l > peakL) peakL = l
            if (r > peakR) peakR = r
            val oldSqL = windowL[winIdx]
            val oldSqR = windowR[winIdx]
            sumSqL += (l * l - oldSqL).toDouble()
            sumSqR += (r * r - oldSqR).toDouble()
            windowL[winIdx] = l * l
            windowR[winIdx] = r * r
            winIdx++
            if (winIdx >= RMS_WINDOW_FRAMES) winIdx = 0
            if (winCount < RMS_WINDOW_FRAMES) winCount++
        }
        val count = if (winCount > 0) winCount else 1
        val rmsLinL = Math.sqrt(sumSqL / count).toFloat()
        val rmsLinR = Math.sqrt(sumSqR / count).toFloat()
        val peakDbL = dbfs(peakL)
        val peakDbR = dbfs(peakR)
        updateHold(0, peakDbL)
        updateHold(1, peakDbR)
        outPeakL = maxOf(peakDbL, heldPeak[0])
        outPeakR = maxOf(peakDbR, heldPeak[1])
        outRmsL = dbfs(rmsLinL)
        outRmsR = dbfs(rmsLinR)
    }

    private fun updateHold(channel: Int, peakDb: Float) {
        if (peakDb > heldPeak[channel]) {
            heldPeak[channel] = peakDb
            holdFrames[channel] = 0
        } else {
            holdFrames[channel]++
            if (holdFrames[channel] > HOLD_RELEASE_FRAMES) {
                heldPeak[channel] -= HOLD_DECAY_DB
                if (heldPeak[channel] < METER_FLOOR_DB) heldPeak[channel] = METER_FLOOR_DB
            }
        }
    }

    private fun maybeEmitLevels() {
        val now = System.nanoTime()
        if (now - lastEmitNanos < EMIT_INTERVAL_NS) return
        lastEmitNanos = now
        val pl = outPeakL
        val rl = outRmsL
        val pr = outPeakR
        val rr = outRmsR
        mainHandler.post { listener.onAudioLevels(pl, rl, pr, rr) }
    }

    private fun notifyError(t: Throwable) {
        Log.e(TAG, "audio error", t)
        mainHandler.post { listener.onError("audio", t) }
    }
}
