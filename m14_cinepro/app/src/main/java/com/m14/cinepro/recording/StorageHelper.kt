package com.m14.cinepro.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FilterOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Unified output handle for every artifact this app contributes to public storage.
 *
 * - API 29+: the row is inserted into MediaStore with IS_PENDING=1; [close](true) closes the
 *   stream and clears IS_PENDING (publishes), [close](false) closes and deletes the pending row.
 * - API <= 28: a direct java.io.File under the requested public directory; [close](true) just
 *   flushes+closes, [close](false) deletes the file.
 */
data class OutputHandle(
    val uri: Uri?,
    val file: File?,
    val stream: OutputStream,
    val close: (Boolean) -> Unit
)

/**
 * Storage layer for M14 CinePro clips, Gyroflow telemetry and slate JSON.
 *
 * API 29+ contributions go through MediaStore (no storage permission needed):
 *   video  -> MediaStore.Video, Movies/M14_CinePro, mime video/mp4
 *   .gcsv  -> MediaStore.Files, Movies/M14_CinePro + DCIM/M14_CinePro copy, application/octet-stream
 *   slate  -> MediaStore.Files, Movies/M14_CinePro + DCIM/M14_CinePro copy, application/json
 *
 * MediaMuxer needs a real FileDescriptor, hence [videoFd] opening the pending video row via
 * openFileDescriptor(uri, "rw"); MediaMuxer(FileDescriptor, format) is available since API 26.
 */
class StorageHelper(private val context: Context) {

    companion object {
        private const val TAG = "M14Rec"

        /** Canonical contribution folders (stage5_media.md + PART 5 spec). */
        const val DIR_MOVIES = "Movies/M14_CinePro"
        const val DIR_DCIM = "DCIM/M14_CinePro"
        private const val MIME_MP4 = "video/mp4"
        private const val MIME_BIN = "application/octet-stream"
        private const val MIME_JSON = "application/json"
        private const val LUT_DIR = "luts"
    }

    private val useMediaStore: Boolean
        get() = Build.VERSION.SDK_INT >= 29

    // ------------------------------------------------------------------
    // Video
    // ------------------------------------------------------------------

    /**
     * Video output as FileDescriptor for MediaMuxer. API 29+ inserts a pending MediaStore.Video
     * row and opens it "rw"; API <= 28 opens a direct file under Movies/M14_CinePro.
     * The returned Uri is a content:// uri (29+) or a file:// uri (legacy, for deletePending).
     */
    fun videoFd(baseName: String): Pair<Uri, ParcelFileDescriptor>? {
        return if (useMediaStore) {
            val resolver = context.contentResolver
            val uri = insertPending(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "$baseName.mp4", MIME_MP4, DIR_MOVIES
            ) ?: return null
            val pfd = try {
                resolver.openFileDescriptor(uri, "rw")
            } catch (e: Exception) {
                Log.e(TAG, "openFileDescriptor failed for $baseName.mp4", e)
                null
            }
            if (pfd == null) {
                try { resolver.delete(uri, null, null) } catch (e: Exception) { Log.w(TAG, "row delete failed", e) }
                return null
            }
            uri to pfd
        } else {
            val file = legacyFile(Environment.DIRECTORY_MOVIES, "$baseName.mp4") ?: return null
            val pfd = try {
                ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE
                )
            } catch (e: Exception) {
                Log.e(TAG, "pfd open failed for ${file.absolutePath}", e)
                null
            } ?: return null
            Uri.fromFile(file) to pfd
        }
    }

    /** Spec API: plain stream pair for the video artifact (publishes on stream close, 29+). */
    fun createVideoFile(baseName: String): Pair<Uri, OutputStream>? {
        val handle = videoHandle(baseName) ?: return null
        val uri = handle.uri ?: handle.file?.let { Uri.fromFile(it) } ?: return null
        return uri to handle.stream
    }

    /** Unified handle variant of [createVideoFile] with commit/abort control. */
    fun videoHandle(baseName: String): OutputHandle? {
        return if (useMediaStore) {
            val resolver = context.contentResolver
            val uri = insertPending(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "$baseName.mp4", MIME_MP4, DIR_MOVIES
            ) ?: return null
            val raw = try {
                resolver.openOutputStream(uri)
            } catch (e: Exception) {
                Log.e(TAG, "openOutputStream failed for $baseName.mp4", e)
                null
            }
            if (raw == null) {
                try { resolver.delete(uri, null, null) } catch (e: Exception) { Log.w(TAG, "row delete failed", e) }
                return null
            }
            buildHandle(uri, null, raw, "$baseName.mp4")
        } else {
            val file = legacyFile(Environment.DIRECTORY_MOVIES, "$baseName.mp4") ?: return null
            val raw = try {
                FileOutputStream(file)
            } catch (e: Exception) {
                Log.e(TAG, "file create failed for ${file.absolutePath}", e)
                null
            } ?: return null
            buildHandle(null, file, raw, "$baseName.mp4")
        }
    }

    // ------------------------------------------------------------------
    // Telemetry (.gcsv) and slate (.json)
    // ------------------------------------------------------------------

    /** Movies/M14_CinePro/<base>.gcsv — Gyroflow telemetry, primary copy. */
    fun telemetryHandle(baseName: String): OutputHandle? =
        openHandle(
            MediaStore.Files.getContentUri("external"), DIR_MOVIES,
            "$baseName.gcsv", MIME_BIN, Environment.DIRECTORY_MOVIES
        )

    /** DCIM/M14_CinePro/<base>.gcsv — identical telemetry copy. */
    fun telemetryHandleDcim(baseName: String): OutputHandle? =
        openHandle(
            MediaStore.Files.getContentUri("external"), DIR_DCIM,
            "$baseName.gcsv", MIME_BIN, Environment.DIRECTORY_DCIM
        )

    /** Movies/M14_CinePro/<base>_slate.json — clip slate metadata. */
    fun slateHandle(baseName: String): OutputHandle? =
        openHandle(
            MediaStore.Files.getContentUri("external"), DIR_MOVIES,
            "${baseName}_slate.json", MIME_JSON, Environment.DIRECTORY_MOVIES
        )

    /** DCIM/M14_CinePro/<base>_slate.json — identical slate copy. */
    fun slateHandleDcim(baseName: String): OutputHandle? =
        openHandle(
            MediaStore.Files.getContentUri("external"), DIR_DCIM,
            "${baseName}_slate.json", MIME_JSON, Environment.DIRECTORY_DCIM
        )

    /** Spec API: raw streams (auto-publish on close, API 29+). */
    fun createTelemetryFile(baseName: String): OutputStream? = telemetryHandle(baseName)?.stream
    fun createTelemetryFileDcim(baseName: String): OutputStream? = telemetryHandleDcim(baseName)?.stream
    fun createSlateFile(baseName: String): OutputStream? = slateHandle(baseName)?.stream

    // ------------------------------------------------------------------
    // Publish / discard
    // ------------------------------------------------------------------

    /** Clears IS_PENDING on a MediaStore row (no-op for file:// uris and API <= 28). */
    fun publish(uri: Uri) {
        if (uri.scheme == "file") return
        if (!useMediaStore) return
        try {
            val cv = ContentValues()
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, cv, null, null)
            Log.i(TAG, "published $uri")
        } catch (e: Exception) {
            Log.e(TAG, "publish failed: $uri", e)
        }
    }

    /** Removes an unfinished contribution (pending row on 29+, file on legacy paths). */
    fun deletePending(uri: Uri) {
        try {
            if (uri.scheme == "file") {
                val f = File(uri.path ?: return)
                if (f.exists()) f.delete()
                return
            }
            if (useMediaStore) {
                context.contentResolver.delete(uri, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "deletePending failed: $uri", e)
        }
    }

    // ------------------------------------------------------------------
    // Volume status + LUT import
    // ------------------------------------------------------------------

    /** Free space on the record volume in GB, two decimals (0f on failure). */
    fun freeGb(): Float = try {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStorageDirectory()
        val stat = StatFs(dir.absolutePath)
        val gb = stat.availableBytes / 1e9
        (Math.round(gb * 100.0) / 100.0).toFloat()
    } catch (e: Exception) {
        Log.w(TAG, "freeGb failed", e)
        0f
    }

    /**
     * Copies a user-picked .cube LUT into app-private external storage
     * (Android/data/<pkg>/files/luts/<name>.cube). Returns the absolute path or null.
     */
    fun importCubeLut(uri: Uri): String? = try {
        val rawName = displayName(uri) ?: "imported_${System.currentTimeMillis()}"
        val safe = rawName
            .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' || it == ' ' }
            .trim()
            .ifBlank { "imported" }
        val fileName = if (safe.endsWith(".cube", ignoreCase = true)) safe else "$safe.cube"
        val dir = context.getExternalFilesDir(LUT_DIR) ?: run {
            Log.e(TAG, "external files dir unavailable")
            return null
        }
        dir.mkdirs()
        val out = File(dir, fileName)
        val ins = context.contentResolver.openInputStream(uri) ?: run {
            Log.e(TAG, "cannot open LUT stream: $uri")
            return null
        }
        ins.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        Log.i(TAG, "LUT imported: ${out.absolutePath} (${out.length()} bytes)")
        out.absolutePath
    } catch (e: Exception) {
        Log.e(TAG, "LUT import failed", e)
        null
    }

    /** Legacy API <= 28 has no content Uri for direct files — always null by contract. */
    fun asUri(file: File): Uri? = null

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun insertPending(collection: Uri, name: String, mime: String, relativePath: String): Uri? = try {
        val cv = ContentValues()
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1)
        context.contentResolver.insert(collection, cv)
    } catch (e: Exception) {
        Log.e(TAG, "MediaStore insert failed ($name @ $relativePath)", e)
        null
    }

    /** Builds a unified handle; MediaStore streams publish IS_PENDING=0 on close. */
    private fun buildHandle(uri: Uri?, file: File?, raw: OutputStream, name: String): OutputHandle {
        return if (uri != null) {
            val resolver = context.contentResolver
            var published = false
            val stream = object : FilterOutputStream(raw) {
                private var closed = false
                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len)
                }
                override fun close() {
                    if (closed) return
                    closed = true
                    try {
                        super.close()
                    } catch (e: IOException) {
                        Log.w(TAG, "stream close failed ($name)", e)
                    }
                    if (!published) {
                        published = true
                        try {
                            val cv = ContentValues()
                            cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                            resolver.update(uri, cv, null, null)
                        } catch (e: Exception) {
                            Log.e(TAG, "publish failed ($name)", e)
                        }
                    }
                }
            }
            OutputHandle(uri, null, stream) { commit ->
                try {
                    stream.close()
                } catch (e: Exception) {
                    Log.w(TAG, "handle close failed ($name)", e)
                }
                if (!commit) {
                    try { resolver.delete(uri, null, null) } catch (e: Exception) { Log.w(TAG, "discard failed ($name)", e) }
                }
            }
        } else {
            val f = file ?: throw IllegalStateException("OutputHandle requires uri or file")
            OutputHandle(null, f, raw) { commit ->
                try {
                    raw.close()
                } catch (e: Exception) {
                    Log.w(TAG, "handle close failed ($name)", e)
                }
                if (!commit) f.delete()
            }
        }
    }

    private fun openHandle(
        collection: Uri,
        relativePath: String,
        name: String,
        mime: String,
        legacyRoot: String
    ): OutputHandle? {
        if (useMediaStore) {
            val resolver = context.contentResolver
            val uri = insertPending(collection, name, mime, relativePath)
            if (uri != null) {
                val raw = try {
                    resolver.openOutputStream(uri)
                } catch (e: Exception) {
                    Log.e(TAG, "openOutputStream failed ($name)", e)
                    null
                }
                if (raw != null) {
                    return buildHandle(uri, null, raw, name)
                }
                try { resolver.delete(uri, null, null) } catch (e: Exception) { Log.w(TAG, "row delete failed", e) }
            }
        }
        val file = legacyFile(legacyRoot, name) ?: return null
        val raw = try {
            FileOutputStream(file)
        } catch (e: Exception) {
            Log.e(TAG, "file create failed ($name)", e)
            null
        } ?: return null
        return buildHandle(null, file, raw, name)
    }

    @Suppress("DEPRECATION")
    private fun legacyFile(rootType: String, name: String): File? = try {
        val root = Environment.getExternalStoragePublicDirectory(rootType)
        val dir = File(root, "M14_CinePro")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "mkdirs failed: ${dir.absolutePath}")
            null
        } else {
            File(dir, name)
        }
    } catch (e: Exception) {
        Log.e(TAG, "legacy path failed ($rootType)", e)
        null
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment
    } catch (e: Exception) {
        uri.lastPathSegment
    }
}
