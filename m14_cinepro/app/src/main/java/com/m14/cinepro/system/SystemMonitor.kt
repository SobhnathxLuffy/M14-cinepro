package com.m14.cinepro.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.m14.cinepro.core.EngineListener
import com.m14.cinepro.recording.StorageHelper

/**
 * M14 CinePro — lightweight system telemetry monitor for the HUD status pill.
 *
 * Emits [EngineListener.onSystemStatus] at >= every [POLL_MS] and additionally on every
 * ACTION_BATTERY_CHANGED system broadcast.
 *
 * Battery %:      EXTRA_LEVEL / EXTRA_SCALE.
 * Temperature:    EXTRA_TEMPERATURE / 10.0f. DOCUMENTED PROXY — the M14 platform does not
 *                 expose a public SoC temperature sensor; the battery temperature tracks
 *                 silicon thermals closely on the Exynos 1330 and is what the stock camera
 *                 app itself uses for its overheat warnings.
 * Thermal headroom: PowerManager.getThermalHeadroom(1) on API 30+, guarded try/catch,
 *                 reported as 0f below API 30 or when the call fails.
 * Free storage:   StorageHelper(context).freeGb() on the record volume.
 *
 * start()/stop() are idempotent; all callbacks are fired on the main looper.
 */
class SystemMonitor(context: Context, private val listener: EngineListener) {

    companion object {
        private const val TAG = "M14Hud"
        private const val POLL_MS = 5000L
    }

    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    @Volatile private var batteryPct = 0
    @Volatile private var tempC = 0f
    @Volatile private var thermalHeadroom = 0f
    @Volatile private var freeGb = 0f

    private var running = false
    private var receiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            parseBattery(intent)
            refreshStorageAndThermal()
            emit()
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            refreshAll()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(batteryReceiver, batteryFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(batteryReceiver, batteryFilter)
            }
            receiverRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "SystemMonitor: battery receiver registration failed", t)
        }
        refreshAll()
        handler.postDelayed(pollRunnable, POLL_MS)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(pollRunnable)
        if (receiverRegistered) {
            receiverRegistered = false
            try {
                appContext.unregisterReceiver(batteryReceiver)
            } catch (t: Throwable) {
                Log.w(TAG, "SystemMonitor: battery receiver unregister failed", t)
            }
        }
    }

    private fun refreshAll() {
        stickyBattery()?.let { parseBattery(it) }
        refreshStorageAndThermal()
        emit()
    }

    private fun stickyBattery(): Intent? = try {
        appContext.registerReceiver(null, batteryFilter)
    } catch (t: Throwable) {
        Log.w(TAG, "SystemMonitor: sticky battery intent unavailable", t)
        null
    }

    private fun parseBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) batteryPct = (level * 100 / scale).coerceIn(0, 100)
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths != Int.MIN_VALUE) tempC = tenths / 10.0f
    }

    private fun refreshStorageAndThermal() {
        freeGb = runCatching { StorageHelper(appContext).freeGb() }.getOrDefault(0f)
        thermalHeadroom = if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.getThermalHeadroom(1)
            }.getOrDefault(0f)
        } else {
            0f // getThermalHeadroom does not exist below API 30; documented proxy above carries the load.
        }
    }

    private fun emit() {
        runCatching {
            listener.onSystemStatus(batteryPct, tempC, thermalHeadroom, freeGb)
        }.onFailure { Log.w(TAG, "SystemMonitor: listener rejected system status", it) }
    }
}
