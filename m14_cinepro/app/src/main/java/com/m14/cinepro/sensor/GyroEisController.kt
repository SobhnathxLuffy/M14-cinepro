package com.m14.cinepro.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.m14.cinepro.core.Models
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Live viewfinder Gyro-EIS for M14 CinePro (Architecture A: the GL viewfinder samples the graded
 * frame through this texture matrix; the zero-GPU record path is untouched).
 *
 * Pipeline: TYPE_ROTATION_VECTOR @ SENSOR_DELAY_GAME on the "GyroEis" HandlerThread ->
 * reference quaternion captured on enable -> per event standard ZYX Tait-Bryan
 * (yaw/pitch/roll) -> deltas vs reference wrapped to [-pi, pi] ->
 * texture-space float[16] M = T(px,py) · T(0.5,0.5,0) · Scale(0.9) · Rz(-dRoll) · T(-0.5,-0.5,0).
 *
 * The inner T(0.5)·S·Rz·T(-0.5) term is "Scale(0.9) composed with rotate-about-center" expressed
 * so the 10% crop (Models.EIS_CROP_SCALE) stays centered: v' = 0.5 + S·Rz·(v - 0.5).
 * The outer pan translate compensates translation: px = -dYaw·PAN_GAIN, py = -dPitch·PAN_GAIN,
 * each clamped to ±PAN_MAX = 0.04 so the sampled window never leaves the 10% crop headroom
 * (crop margin is 0.05). Renderer polls [matrix] (thread-safe copy) every frame.
 */
class GyroEisController(private val context: Context) {

    companion object {
        private const val TAG = "M14Eis"
        private const val PAN_GAIN = 0.22f
        private const val PAN_MAX = 0.04f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val thread = HandlerThread("GyroEis").also { it.start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()

    // Quaternions stored [w, x, y, z]; reference captured on first event after enable.
    private val refQ = FloatArray(4)
    private val curQ = FloatArray(4)
    @Volatile private var refCaptured = false
    @Volatile private var enabled = false
    @Volatile private var latest: FloatArray? = null

    // Scratch matrices (only touched on the GyroEis thread).
    private val mR = FloatArray(16)
    private val mTn = FloatArray(16)
    private val mTp = FloatArray(16)
    private val mS = FloatArray(16)
    private val mP = FloatArray(16)
    private val mA = FloatArray(16)
    private val mB = FloatArray(16)
    private val mOut = FloatArray(16)

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            process(event)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // rotation vector accuracy is not used by the transform
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Enables/disables the stabilizer; the reference orientation is (re)captured on enable. */
    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        if (on) {
            val sensor = rotationSensor
            if (sensor == null) {
                Log.w(TAG, "TYPE_ROTATION_VECTOR unavailable on this device — gyro-EIS disabled")
                return
            }
            handler.post {
                // THREAD_PRIORITY_DISPLAY (-4): transform math must not add sensor latency to
                // the viewfinder chain; codec audio/telemetry threads use dedicated priorities.
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                refCaptured = false
                sensorManager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
            }
            Log.i(TAG, "gyro-EIS enabled (crop 0.9, pan gain $PAN_GAIN, max pan $PAN_MAX)")
        } else {
            try {
                sensorManager.unregisterListener(sensorListener)
            } catch (e: Exception) {
                Log.w(TAG, "unregister failed", e)
            }
            synchronized(lock) { latest = null }
            Log.i(TAG, "gyro-EIS disabled")
        }
    }

    /** Latest texture matrix or null when disabled / no sample yet. Fresh copy, safe for GL upload. */
    fun matrix(): FloatArray? = synchronized(lock) { latest?.copyOf() }

    /** Stops sensor delivery and quits the thread (call on Activity teardown). */
    fun release() {
        setEnabled(false)
        thread.quitSafely()
    }

    // ------------------------------------------------------------------
    // Math
    // ------------------------------------------------------------------

    private fun process(event: SensorEvent) {
        val v = event.values
        if (v.size < 3) return
        // Rotation vector: v[0..2] = axis*sin(theta/2), v[3] = cos(theta/2) (API >= 18).
        var x = v[0].toDouble()
        var y = v[1].toDouble()
        var z = v[2].toDouble()
        var w = if (v.size >= 4) v[3].toDouble() else sqrt((1.0 - x * x - y * y - z * z).coerceAtLeast(0.0))
        val norm = sqrt(w * w + x * x + y * y + z * z)
        if (norm <= 0.0) return
        w /= norm
        x /= norm
        y /= norm
        z /= norm
        curQ[0] = w.toFloat()
        curQ[1] = x.toFloat()
        curQ[2] = y.toFloat()
        curQ[3] = z.toFloat()
        if (!refCaptured) {
            System.arraycopy(curQ, 0, refQ, 0, 4)
            refCaptured = true
            return
        }
        val dYaw = wrapPi(yawOf(curQ) - yawOf(refQ))
        val dPitch = wrapPi(pitchOf(curQ) - pitchOf(refQ))
        val dRoll = wrapPi(rollOf(curQ) - rollOf(refQ))
        val m = buildMatrix(dYaw, dPitch, dRoll)
        synchronized(lock) { latest = m }
    }

    /** Standard ZYX Tait-Bryan extraction from [w, x, y, z]. */
    private fun yawOf(q: FloatArray): Double {
        val w = q[0].toDouble()
        val x = q[1].toDouble()
        val y = q[2].toDouble()
        val z = q[3].toDouble()
        return atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z))
    }

    private fun pitchOf(q: FloatArray): Double {
        val w = q[0].toDouble()
        val x = q[1].toDouble()
        val y = q[2].toDouble()
        val z = q[3].toDouble()
        return asin((2.0 * (w * y - z * x)).coerceIn(-1.0, 1.0))
    }

    private fun rollOf(q: FloatArray): Double {
        val w = q[0].toDouble()
        val x = q[1].toDouble()
        val y = q[2].toDouble()
        val z = q[3].toDouble()
        return atan2(2.0 * (w * x + y * z), 1.0 - 2.0 * (x * x + y * y))
    }

    private fun wrapPi(a: Double): Double {
        val twoPi = 2.0 * Math.PI
        var r = (a + Math.PI) % twoPi
        if (r < 0) r += twoPi
        return r - Math.PI
    }

    /**
     * M = T(px,py) · T(0.5,0.5,0) · Scale(S) · Rz(-dRoll) · T(-0.5,-0.5,0)
     * applied as v' = 0.5 + S·Rz(-dRoll)·(v - 0.5) + p  (column-vector convention, column-major storage).
     */
    private fun buildMatrix(dYaw: Double, dPitch: Double, dRoll: Double): FloatArray {
        val px = (-dYaw * PAN_GAIN).toFloat().coerceIn(-PAN_MAX, PAN_MAX)
        val py = (-dPitch * PAN_GAIN).toFloat().coerceIn(-PAN_MAX, PAN_MAX)
        val rollDeg = Math.toDegrees(-dRoll).toFloat()

        Matrix.setIdentityM(mR, 0)
        Matrix.rotateM(mR, 0, rollDeg, 0f, 0f, 1f)
        Matrix.setIdentityM(mTn, 0)
        Matrix.translateM(mTn, 0, -0.5f, -0.5f, 0f)
        Matrix.setIdentityM(mTp, 0)
        Matrix.translateM(mTp, 0, 0.5f, 0.5f, 0f)
        Matrix.setIdentityM(mS, 0)
        Matrix.scaleM(mS, 0, Models.EIS_CROP_SCALE, Models.EIS_CROP_SCALE, 1f)
        Matrix.setIdentityM(mP, 0)
        Matrix.translateM(mP, 0, px, py, 0f)

        // t1 = Rz · T(-0.5)   (T(-0.5) applied first)
        Matrix.multiplyMM(mA, 0, mR, 0, mTn, 0)
        // t2 = Scale(S) · t1
        Matrix.multiplyMM(mB, 0, mS, 0, mA, 0)
        // t3 = T(0.5) · t2    -> scale+rotate about texture center
        Matrix.multiplyMM(mA, 0, mTp, 0, mB, 0)
        // M  = T(px,py) · t3  -> pan inside the crop headroom
        Matrix.multiplyMM(mOut, 0, mP, 0, mA, 0)
        return mOut.copyOf()
    }
}
