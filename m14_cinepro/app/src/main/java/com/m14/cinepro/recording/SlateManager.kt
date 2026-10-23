package com.m14.cinepro.recording

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.m14.cinepro.core.Models
import com.m14.cinepro.core.SettingsStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Slate / clip naming for M14 CinePro (stage5_media.md BmdMetadata registry contract).
 *
 * - [clipBaseName] builds "M14_R<reel>_S<scene>_T<take>_<yyyyMMdd_HHmmss>" with the scene field
 *   sanitized to alnum + '-_' only (file-system safe).
 * - [nextTake] bumps and persists settings.take (called by RecordingController after a take
 *   finalizes so the next clip rolls the take number).
 * - [writeSlateJson] emits the per-clip slate registry to BOTH Movies/M14_CinePro and
 *   DCIM/M14_CinePro as <baseName>_slate.json, then resets the good-take flag (consumed once).
 */
class SlateManager(private val context: Context, private val settings: SettingsStore) {

    companion object {
        private const val TAG = "M14Rec"
    }

    /** Bumps the persistent take counter and returns the new value. */
    fun nextTake(): Int {
        val next = settings.take + 1
        settings.take = next
        return next
    }

    /** "M14_R<reel>_S<scene>_T<take>_<yyyyMMdd_HHmmss>" — base name without extension. */
    fun clipBaseName(): String {
        val scene = settings.scene
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .ifBlank { "NA" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return String.format(Locale.US, "M14_R%d_S%s_T%d_%s", settings.reel, scene, settings.take, stamp)
    }

    /**
     * Writes <baseName>_slate.json (Movies + DCIM copies) containing the full slate registry.
     * Returns the JSONObject (also used by callers that want to display/verify the slate).
     */
    fun writeSlateJson(baseName: String, storage: StorageHelper): JSONObject {
        val goodTake = settings.goodTake
        val recordedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())

        val json = JSONObject()
        json.put("reel", settings.reel)
        json.put("scene", settings.scene)
        json.put("take", settings.take)
        json.put("goodTake", goodTake)
        json.put("director", settings.director)
        json.put("operator", settings.operator)
        json.put("resolution", settings.resolution.label)
        json.put("fps", settings.fps)
        json.put(
            "shutterAngle",
            if (settings.shutterMode == Models.ShutterMode.ANGLE) {
                String.format(Locale.US, "%.1f°", settings.shutterAngleDeg)
            } else {
                ""
            }
        )
        json.put(
            "shutterSpeed",
            if (settings.shutterMode == Models.ShutterMode.SPEED) {
                "1/${settings.shutterSpeedDenominator} s"
            } else {
                ""
            }
        )
        json.put("iso", settings.iso)
        json.put("kelvin", settings.kelvin)
        json.put("tint", settings.tint)
        json.put("lut", settings.lutPreset.name)
        json.put("bitrate", settings.bitrate.bps)
        json.put("clipName", "$baseName.mp4")
        json.put("recordedAtIso8601", recordedAt)
        json.put("appVersion", appVersion())
        json.put("deviceModel", Build.MODEL)

        val body = json.toString(2)
        val bytes = body.toByteArray(Charsets.UTF_8)
        for (handle in listOfNotNull(storage.slateHandle(baseName), storage.slateHandleDcim(baseName))) {
            try {
                handle.stream.write(bytes)
                handle.stream.flush()
            } catch (e: Exception) {
                Log.e(TAG, "slate write failed", e)
            } finally {
                try { handle.close(true) } catch (e: Exception) { Log.w(TAG, "slate handle close failed", e) }
            }
        }
        settings.goodTake = false // consume the good-take flag once slated
        Log.i(TAG, "slate written for $baseName (goodTake=$goodTake)")
        return json
    }

    private fun appVersion(): String = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L)).versionName
                ?: "unknown"
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        }
    } catch (e: Exception) {
        "unknown"
    }
}
