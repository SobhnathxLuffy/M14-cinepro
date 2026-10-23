package com.m14.cinepro.recording

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Gyroflow v1.3 telemetry logger for the Galaxy M14 5G LSM6DSL IMU (M14_VERIFIED_TRUTH §2:
 * gyroscope + accelerometer, both continuous up to 500.00 Hz, FIFO 1000 events).
 *
 * Registers TYPE_GYROSCOPE + TYPE_ACCELEROMETER with SENSOR_DELAY_FASTEST on the "GyroLogger"
 * HandlerThread. Thread priority is THREAD_PRIORITY_MORE_FAVORABLE (-1): the LSM6DSL fires at
 * 500 Hz per axis and the default sensor delivery latency (THREAD_PRIORITY_BACKGROUND = +10)
 * measurably bunches event timestamps, which would quantize the .gcsv timeline; -1 keeps the
 * dispatcher above the default UI priority without starving the codec/HUD threads (which run at
 * -4 and below). Rows are produced at ~500 Hz and consumed by the dedicated "GyroWriter" thread
 * through a ConcurrentLinkedQueue; every row is written to BOTH the Movies/M14_CinePro and the
 * DCIM/M14_CinePro copies.
 *
 * Each GYRO event is paired with the LATEST accelerometer sample (both streams are ~500 Hz).
 */
class GyroflowLogger(private val context: Context) {

    companion object {
        private const val TAG = "M14Gyro"
        private const val SENSOR_PRIORITY = Process.THREAD_PRIORITY_MORE_FAVORABLE // -1, see class doc
        private const val WRITER_IDLE_MS = 20L
        private const val WRITER_FLUSH_EVERY = 256
    }

    /** Optional failure hook — RecordingController forwards it to EngineListener.onError. */
    @Volatile var errorListener: ((stage: String, throwable: Throwable) -> Unit)? = null

    private val storage = StorageHelper(context)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val queue = ConcurrentLinkedQueue<String>()
    private val latestAccel = FloatArray(3)

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var writerThread: Thread? = null
    private var moviesWriter: BufferedWriter? = null
    private var dcimWriter: BufferedWriter? = null
    private var moviesHandle: OutputHandle? = null
    private var dcimHandle: OutputHandle? = null
    private var writerBroken = false

    @Volatile private var running = false

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!running) return
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> synchronized(latestAccel) {
                    latestAccel[0] = value(event, 0)
                    latestAccel[1] = value(event, 1)
                    latestAccel[2] = value(event, 2)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    // kotlin.synchronized is inline, so the block result flows out directly.
                    val accel = synchronized(latestAccel) {
                        floatArrayOf(latestAccel[0], latestAccel[1], latestAccel[2])
                    }
                    // SensorEvent.timestamp is ns since boot -> µs row time (tscale:1 contract).
                    val tUs = event.timestamp / 1000
                    queue.offer(
                        String.format(
                            Locale.US, "%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",
                            tUs, value(event, 0), value(event, 1), value(event, 2),
                            accel[0], accel[1], accel[2]
                        )
                    )
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // not tracked — the .gcsv format has no accuracy column
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Starts logging to <baseName>.gcsv in Movies/M14_CinePro + DCIM/M14_CinePro.
     * Writes the exact Gyroflow v1.3 header synchronously, then streams data rows.
     */
    @Synchronized
    fun start(baseName: String, note: String) {
        if (running) stop() // reentrant: clean restart
        if (gyroSensor == null) {
            val e = RuntimeException("no gyroscope on device — telemetry disabled")
            Log.e(TAG, "start failed", e)
            errorListener?.invoke("gyro", e)
            return
        }
        moviesHandle = storage.telemetryHandle(baseName)
        dcimHandle = storage.telemetryHandleDcim(baseName)
        if (moviesHandle == null && dcimHandle == null) {
            val e = RuntimeException("cannot create telemetry output for $baseName.gcsv")
            Log.e(TAG, "start failed", e)
            errorListener?.invoke("gyro", e)
            return
        }
        try {
            moviesWriter = moviesHandle?.let {
                BufferedWriter(OutputStreamWriter(it.stream, Charsets.UTF_8))
            }
            dcimWriter = dcimHandle?.let {
                BufferedWriter(OutputStreamWriter(it.stream, Charsets.UTF_8))
            }
            writeHeader(note)
        } catch (e: Exception) {
            Log.e(TAG, "telemetry header write failed", e)
            errorListener?.invoke("gyro", e)
            flushAndCloseWriters()
            return
        }
        synchronized(latestAccel) { latestAccel.fill(0f) }
        queue.clear()
        writerBroken = false
        running = true

        val thread = HandlerThread("GyroLogger")
        thread.start()
        sensorThread = thread
        val handler = Handler(thread.looper)
        sensorHandler = handler
        // Priority set on the sensor thread itself (Process.setThreadPriority on a live tid).
        handler.post { Process.setThreadPriority(SENSOR_PRIORITY) }
        sensorManager.registerListener(sensorListener, gyroSensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
        if (accelSensor != null) {
            sensorManager.registerListener(sensorListener, accelSensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
        }
        val writer = Thread({ writerLoop() }, "GyroWriter")
        writerThread = writer
        writer.start()
        val primary = moviesHandle?.uri?.toString() ?: moviesHandle?.file?.absolutePath ?: "?"
        Log.i(TAG, "gyro logging started -> $primary (LSM6DSL @ 500 Hz expected)")
    }

    /** Unregisters listeners and flushes + closes (publishes) both copies. Idempotent. */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try {
            sensorManager.unregisterListener(sensorListener)
        } catch (e: Exception) {
            Log.w(TAG, "unregister failed", e)
        }
        writerThread?.let { t ->
            try { t.join(2500) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
        writerThread = null
        sensorHandler = null
        sensorThread?.quitSafely()
        sensorThread = null
        Log.i(TAG, "gyro logging stopped")
    }

    /** MediaStore Uri (API 29+) or null of the primary Movies copy. */
    fun uri(): Uri? = moviesHandle?.uri

    /** Direct file (API <= 28) or null of the primary Movies copy. */
    fun file(): File? = moviesHandle?.file

    // ------------------------------------------------------------------
    // Writer
    // ------------------------------------------------------------------

    private fun writeHeader(note: String) {
        val sanitizedNote = note.replace('\n', ' ').replace('\r', ' ')
        val header = buildString {
            append("gyroflow v1.3\n")
            append("lens_profile:Custom\n")
            append("calibrator:M14 CinePro\n")
            append("orientation:XYZ\n")
            append("note:").append(sanitizedNote).append('\n')
            append("format:t_us,gx,gy,gz,ax,ay,az\n")
            append("tscale:1\n")
        }
        moviesWriter?.write(header)
        dcimWriter?.write(header)
        moviesWriter?.flush()
        dcimWriter?.flush()
    }

    private fun writerLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        var sinceFlush = 0
        try {
            while (true) {
                val line = queue.poll()
                if (line != null) {
                    writeTo(moviesWriter, line, "movies")
                    writeTo(dcimWriter, line, "dcim")
                    if (++sinceFlush >= WRITER_FLUSH_EVERY) {
                        try { moviesWriter?.flush() } catch (e: IOException) { disableMovies(e) }
                        try { dcimWriter?.flush() } catch (e: IOException) { disableDcim(e) }
                        sinceFlush = 0
                    }
                } else {
                    if (!running && queue.isEmpty()) break
                    try { moviesWriter?.flush() } catch (e: IOException) { disableMovies(e) }
                    try { dcimWriter?.flush() } catch (e: IOException) { disableDcim(e) }
                    sinceFlush = 0
                    Thread.sleep(WRITER_IDLE_MS)
                }
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "GyroWriter interrupted")
        } finally {
            flushAndCloseWriters()
        }
    }

    private fun writeTo(w: BufferedWriter?, line: String, label: String) {
        if (w == null) return
        try {
            w.write(line)
            w.write("\n")
        } catch (e: IOException) {
            Log.e(TAG, "$label telemetry write failed — copy disabled", e)
            if (label == "movies") disableMovies(e) else disableDcim(e)
        }
    }

    private fun disableMovies(e: IOException) {
        if (!writerBroken) {
            writerBroken = true
            errorListener?.invoke("gyro", e)
        }
        try { moviesWriter?.close() } catch (ignore: IOException) { }
        moviesWriter = null
    }

    private fun disableDcim(e: IOException) {
        try { dcimWriter?.close() } catch (ignore: IOException) { }
        dcimWriter = null
    }

    /** Final flush + close; closing the underlying streams publishes the MediaStore rows. */
    private fun flushAndCloseWriters() {
        try { moviesWriter?.flush() } catch (e: IOException) { Log.w(TAG, "movies flush", e) }
        try { dcimWriter?.flush() } catch (e: IOException) { Log.w(TAG, "dcim flush", e) }
        try { moviesWriter?.close() } catch (e: IOException) { Log.w(TAG, "movies close", e) }
        try { dcimWriter?.close() } catch (e: IOException) { Log.w(TAG, "dcim close", e) }
        moviesWriter = null
        dcimWriter = null
        moviesHandle?.let { h ->
            try { h.close(true) } catch (e: Exception) { Log.w(TAG, "movies handle close", e) }
        }
        dcimHandle?.let { h ->
            try { h.close(true) } catch (e: Exception) { Log.w(TAG, "dcim handle close", e) }
        }
    }

    private fun value(event: SensorEvent, index: Int): Float =
        if (event.values.size > index) event.values[index] else 0f
}
