package com.m14.cinepro.recording

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONObject
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

        /** Display-name prefix produced by SlateManager.clipBaseName(). */
        private const val CLIP_PREFIX = "M14_"
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
    // Media Pool (QA Phase 5)
    // ------------------------------------------------------------------

    /** Media Pool row (QA Phase 5). */
    data class ClipInfo(
        val name: String,       // base name WITHOUT extension
        val uri: Uri?,          // content:// (29+) or file:// uri for playback/share
        val file: File?,        // legacy direct file (API <= 28)
        val sizeBytes: Long,
        val dateMs: Long,
        val widthPx: Int,
        val heightPx: Int,
        val hasGcsv: Boolean,
        val hasSlate: Boolean,
        val gcsvUri: Uri?,
        val gcsvFile: File?
    )

    /**
     * All published clips in Movies/M14_CinePro, newest first (QA Phase 5).
     *
     * API 29+ queries MediaStore.Video with a server-side RELATIVE_PATH filter
     * ("Movies/M14_CinePro%") sorted DATE_MODIFIED DESC; devices that expose RELATIVE_PATH as
     * null (or reject the selection) fall back to an unfiltered query filtered client-side by
     * the "M14_" display-name prefix + path check. API <= 28 lists the legacy
     * Movies/M14_CinePro directory. gcsv/slate presence is probed per clip afterwards.
     */
    fun listClips(): List<ClipInfo> {
        val clips = if (useMediaStore) listClipsMediaStore() else listClipsLegacy()
        return clips.sortedByDescending { it.dateMs } // uniform "newest first" for both paths
    }

    private fun listClipsMediaStore(): List<ClipInfo> {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val sort = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        // Primary path: server-side folder filter (API 29+).
        try {
            resolver.query(
                collection, projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$DIR_MOVIES%"),
                sort
            )?.use { c -> return collectClipRows(c, requirePrefix = false) }
        } catch (e: Exception) {
            Log.w(TAG, "RELATIVE_PATH clip query failed — client-side fallback", e)
        }
        // Fallback: RELATIVE_PATH null on some devices -> filter by "M14_" prefix + path here.
        return try {
            resolver.query(collection, projection, null, null, sort)
                ?.use { c -> collectClipRows(c, requirePrefix = true) }
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore clip listing failed", e)
            emptyList()
        }
    }

    /** Maps a MediaStore.Video cursor into ClipInfos; [requirePrefix] also path-checks rows. */
    private fun collectClipRows(cursor: Cursor, requirePrefix: Boolean): List<ClipInfo> {
        val out = ArrayList<ClipInfo>()
        while (cursor.moveToNext()) {
            val displayName = cursor.getString(0) ?: continue
            if (!displayName.endsWith(".mp4")) continue
            if (requirePrefix) {
                if (!displayName.startsWith(CLIP_PREFIX)) continue
                val relPath = if (cursor.columnCount > 6) cursor.getString(6) else null
                if (relPath != null && !relPath.startsWith("Movies/M14_CinePro")) continue
            }
            val base = displayName.removeSuffix(".mp4")
            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(1))
            val gcsv = fileRowUri("$base.gcsv", DIR_MOVIES)
            out.add(
                ClipInfo(
                    name = base,
                    uri = uri,
                    file = null,
                    sizeBytes = if (cursor.isNull(2)) 0L else cursor.getLong(2),
                    dateMs = if (cursor.isNull(3)) 0L else cursor.getLong(3) * 1000L,
                    widthPx = if (cursor.columnCount > 4 && !cursor.isNull(4)) cursor.getInt(4) else 0,
                    heightPx = if (cursor.columnCount > 5 && !cursor.isNull(5)) cursor.getInt(5) else 0,
                    hasGcsv = gcsv != null,
                    hasSlate = fileRowUri("${base}_slate.json", DIR_MOVIES) != null,
                    gcsvUri = gcsv,
                    gcsvFile = null
                )
            )
        }
        return out
    }

    /** Legacy (API <= 28) clip listing of Movies/M14_CinePro (same dir logic as [legacyFile]). */
    private fun listClipsLegacy(): List<ClipInfo> {
        val out = ArrayList<ClipInfo>()
        val movies = legacyExistingDir(Environment.DIRECTORY_MOVIES)
        val dcim = legacyExistingDir(Environment.DIRECTORY_DCIM)
        val files = movies?.listFiles { f ->
            f.isFile && f.name.startsWith(CLIP_PREFIX) && f.name.endsWith(".mp4")
        } ?: emptyArray()
        for (f in files) {
            val base = f.name.removeSuffix(".mp4")
            val gcsv = listOfNotNull(
                File(movies, "$base.gcsv"),
                dcim?.let { File(it, "$base.gcsv") }
            ).firstOrNull { it.exists() }
            out.add(
                ClipInfo(
                    name = base,
                    uri = Uri.fromFile(f),
                    file = f,
                    sizeBytes = f.length(),
                    dateMs = f.lastModified(),
                    widthPx = 0,
                    heightPx = 0,
                    hasGcsv = gcsv != null,
                    hasSlate = File(movies, "${base}_slate.json").exists() ||
                        (dcim?.let { File(it, "${base}_slate.json") }?.exists() == true),
                    gcsvUri = gcsv?.let { Uri.fromFile(it) },
                    gcsvFile = gcsv
                )
            )
        }
        return out
    }

    /** Deletes the clip mp4 plus its .gcsv / _slate.json siblings when reachable. */
    fun deleteClip(info: ClipInfo): Boolean {
        var ok = false
        if (useMediaStore) {
            val uri = info.uri
            if (uri != null) {
                ok = try {
                    context.contentResolver.delete(uri, null, null) > 0
                } catch (e: Exception) {
                    Log.e(TAG, "clip delete failed: $uri", e)
                    false
                }
                for (name in arrayOf("${info.name}.gcsv", "${info.name}_slate.json")) {
                    for ((_, row) in fileRowUris(name)) {
                        try {
                            context.contentResolver.delete(row, null, null)
                        } catch (e: Exception) {
                            Log.w(TAG, "sibling delete failed: $row", e)
                        }
                    }
                }
            }
        } else {
            val f = info.file
            ok = f != null && f.delete()
            for (rootType in arrayOf(Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_DCIM)) {
                val dir = legacyExistingDir(rootType) ?: continue
                File(dir, "${info.name}.gcsv").delete()
                File(dir, "${info.name}_slate.json").delete()
            }
        }
        if (ok) Log.i(TAG, "clip deleted: ${info.name}")
        return ok
    }

    /** Slate JSON text of [info] — Movies copy preferred, DCIM mirror fallback (both paths). */
    fun readSlateJson(info: ClipInfo): String? {
        if (!useMediaStore) {
            for (rootType in arrayOf(Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_DCIM)) {
                val dir = legacyExistingDir(rootType) ?: continue
                val f = File(dir, "${info.name}_slate.json")
                if (!f.exists()) continue
                try {
                    return f.readText(Charsets.UTF_8)
                } catch (e: Exception) {
                    Log.w(TAG, "legacy slate read failed: ${f.absolutePath}", e)
                }
            }
            return null
        }
        val rows = fileRowUris("${info.name}_slate.json")
            .sortedByDescending { slatePathRank(it.first) } // Movies first, DCIM second, rest last
        for ((_, uri) in rows) {
            readTextFrom(uri)?.let { return it }
        }
        return null
    }

    /**
     * Flips the "goodTake" flag inside <base>_slate.json (Movies + DCIM copies when reachable):
     * existing rows/files are deleted and rewritten through [slateHandle]/[slateHandleDcim],
     * the same handle style SlateManager uses. Returns true only when the Movies copy succeeded.
     */
    fun writeGoodTake(info: ClipInfo, goodTake: Boolean): Boolean {
        val json = try {
            val text = readSlateJson(info)
            (if (text.isNullOrBlank()) JSONObject() else JSONObject(text)).put("goodTake", goodTake)
        } catch (e: Exception) {
            Log.w(TAG, "slate parse failed for ${info.name} — writing fresh registry", e)
            JSONObject().put("goodTake", goodTake)
        }
        val body = json.toString(2).toByteArray(Charsets.UTF_8)

        // Drop the stale copies first so the rewritten JSON is the only one on disk.
        if (useMediaStore) {
            for ((_, row) in fileRowUris("${info.name}_slate.json")) {
                try {
                    context.contentResolver.delete(row, null, null)
                } catch (e: Exception) {
                    Log.w(TAG, "stale slate delete failed: $row", e)
                }
            }
        } else {
            for (rootType in arrayOf(Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_DCIM)) {
                val dir = legacyExistingDir(rootType) ?: continue
                File(dir, "${info.name}_slate.json").delete()
            }
        }

        var moviesOk = false
        val moviesHandle = slateHandle(info.name)
        if (moviesHandle != null) {
            try {
                moviesHandle.stream.write(body)
                moviesHandle.stream.flush()
                moviesOk = true
            } catch (e: Exception) {
                Log.e(TAG, "slate rewrite failed (Movies/${info.name}_slate.json)", e)
            } finally {
                try { moviesHandle.close(moviesOk) } catch (e: Exception) { Log.w(TAG, "slate handle close failed", e) }
            }
        }
        val dcimHandle = slateHandleDcim(info.name) // best effort mirror
        if (dcimHandle != null) {
            try {
                dcimHandle.stream.write(body)
                dcimHandle.stream.flush()
            } catch (e: Exception) {
                Log.w(TAG, "slate rewrite failed (DCIM copy, best effort)", e)
            } finally {
                try { dcimHandle.close(true) } catch (e: Exception) { Log.w(TAG, "slate handle close failed", e) }
            }
        }
        if (moviesOk) Log.i(TAG, "goodTake=$goodTake written for ${info.name}")
        return moviesOk
    }

    /**
     * MediaStore.Files rows named [displayName] as (RELATIVE_PATH, uri) pairs. Kept in
     * try/catch: provider quirk must never take the Media Pool down (QA Phase 6D).
     */
    private fun fileRowUris(displayName: String): List<Pair<String?, Uri>> = try {
        val collection = MediaStore.Files.getContentUri("external")
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null
        )?.use { c ->
            val rows = ArrayList<Pair<String?, Uri>>(c.count)
            while (c.moveToNext()) {
                rows.add(
                    if (c.columnCount > 1) c.getString(1) to ContentUris.withAppendedId(collection, c.getLong(0))
                    else null to ContentUris.withAppendedId(collection, c.getLong(0))
                )
            }
            rows
        } ?: emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "MediaStore file lookup failed ($displayName)", e)
        emptyList()
    }

    /** Best row for [displayName]: [preferDir] first, then any (null when absent). */
    private fun fileRowUri(displayName: String, preferDir: String? = null): Uri? {
        val rows = fileRowUris(displayName)
        if (rows.isEmpty()) return null
        if (preferDir != null) {
            rows.firstOrNull { it.first?.startsWith(preferDir) == true }?.let { return it.second }
        }
        return rows.first().second
    }

    /** Slate copy ranking: Movies/M14_CinePro = 2, DCIM/M14_CinePro = 1, anything else = 0. */
    private fun slatePathRank(relativePath: String?): Int = when {
        relativePath?.startsWith("Movies/M14_CinePro") == true -> 2
        relativePath?.startsWith("DCIM/M14_CinePro") == true -> 1
        else -> 0
    }

    /** Reads UTF-8 text from a content uri; null (with log) on any failure. */
    private fun readTextFrom(uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
    } catch (e: Exception) {
        Log.w(TAG, "slate read failed: $uri", e)
        null
    }

    /** Existing (never mkdirs) legacy contribution dir, same path logic as [legacyFile]. */
    @Suppress("DEPRECATION")
    private fun legacyExistingDir(rootType: String): File? = try {
        File(Environment.getExternalStoragePublicDirectory(rootType), "M14_CinePro")
    } catch (e: Exception) {
        Log.w(TAG, "legacy dir resolve failed ($rootType)", e)
        null
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
