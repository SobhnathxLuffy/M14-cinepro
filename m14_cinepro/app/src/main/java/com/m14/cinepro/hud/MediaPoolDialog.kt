package com.m14.cinepro.hud

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import com.m14.cinepro.R
import com.m14.cinepro.recording.StorageHelper
import org.json.JSONObject
import java.util.Locale

/**
 * M14 CinePro — full-screen dark cinema Media Pool (QA Phase 5).
 *
 * Programmatic UI only (project law: no layout XML for HUD), pure framework widgets:
 * [Dialog] shell, [ListView] + [BaseAdapter] clip rows, [VideoView] + [SeekBar] playback
 * panel, [AlertDialog] for slate view / delete confirm. Clips come from
 * [StorageHelper.listClips] (Movies/M14_CinePro, newest first); rows expose play, slate JSON
 * viewer, mp4 share (+ .gcsv share when telemetry exists), good-take flag toggle and delete.
 * TAG "M14Pool".
 */
class MediaPoolDialog private constructor(
    private val activity: Activity,
    private val storage: StorageHelper
) {

    companion object {
        private const val TAG = "M14Pool"

        /** Playback panel update cadence (seek + timecode). */
        private const val UPDATE_MS = 250L

        /** Full-screen shell background: near-opaque dark with the activity dimmed behind. */
        private const val SHELL_BG = 0xF20A0C10.toInt()

        fun show(activity: Activity, storage: StorageHelper) {
            MediaPoolDialog(activity, storage).open()
        }
    }

    private val density = activity.resources.displayMetrics.density
    private val textPrimary = activity.getColor(R.color.hud_text_primary)
    private val textDim = activity.getColor(R.color.hud_text_dim)
    private val accent = activity.getColor(R.color.hud_accent)
    private val pillBgColor = activity.getColor(R.color.hud_pill_bg)
    private val panelBg = activity.getColor(R.color.panel_bg)

    private lateinit var dialog: Dialog
    private lateinit var playerPanel: LinearLayout
    private lateinit var videoView: VideoView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseButton: Button
    private lateinit var timecodeView: TextView
    private lateinit var listView: ListView

    private var clips: List<StorageHelper.ClipInfo> = emptyList()
    private var currentClip: StorageHelper.ClipInfo? = null
    private var scrubbing = false
    private val goodTakeCache = HashMap<String, Boolean>()
    private val uiHandler = Handler(Looper.getMainLooper())

    /** 250 ms loop: seek + "MM:SS / MM:SS" timecode while a clip is loaded/playing. */
    private val updateTick = object : Runnable {
        override fun run() {
            if (!this@MediaPoolDialog::videoView.isInitialized) return
            val duration = videoView.duration.coerceAtLeast(0)
            val pos = videoView.currentPosition.coerceIn(0, duration)
            if (!scrubbing) seekBar.progress = pos
            timecodeView.text = String.format(
                Locale.US, "%02d:%02d / %02d:%02d",
                pos / 60000, (pos / 1000) % 60, duration / 60000, (duration / 1000) % 60
            )
            val nowPlaying = videoView.isPlaying
            playPauseButton.setText(
                if (nowPlaying) R.string.media_pool_pause else R.string.media_pool_play
            )
            uiHandler.postDelayed(this, UPDATE_MS)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Shell
    // -----------------------------------------------------------------------------------------

    private fun open() {
        dialog = Dialog(activity)
        dialog.setContentView(buildShell())
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(SHELL_BG))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
            applyImmersive(this)
        }
        dialog.setOnDismissListener {
            uiHandler.removeCallbacks(updateTick)
            currentClip = null
            runCatching { videoView.stopPlayback() }
                .onFailure { Log.w(TAG, "stopPlayback failed", it) }
        }
        dialog.show()
        refresh()
    }

    /** Immersive-sticky for the dialog window (API 30 insets controller, legacy flags before). */
    private fun applyImmersive(w: Window) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                w.setDecorFitsSystemWindows(false)
                w.insetsController?.hide(WindowInsets.Type.systemBars())
            } catch (t: Throwable) {
                Log.w(TAG, "media pool insets controller unavailable", t)
            }
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun buildShell(): View {
        fun dp(v: Float): Int = (v * density + 0.5f).toInt()

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(6f))
        }
        header.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.media_pool_title)
                setTextColor(textPrimary)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            chip("\u2715 CLOSE").apply {
                setOnClickListener { dialog.dismiss() }
            }
        )

        buildPlayerPanel(::dp)
        buildList()

        val shell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SHELL_BG)
            addView(header)
            addView(
                playerPanel,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                listView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                ).apply { topMargin = dp(4f) }
            )
        }
        return shell
    }

    // -----------------------------------------------------------------------------------------
    // Player panel (GONE until a clip plays)
    // -----------------------------------------------------------------------------------------

    private fun buildPlayerPanel(dp: (Float) -> Int) {
        playerPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16f), dp(4f), dp(16f), dp(4f))
        }

        videoView = VideoView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200f)
            )
        }
        playerPanel.addView(videoView)

        seekBar = SeekBar(activity).apply {
            max = 0
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) videoView.seekTo(progress)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    scrubbing = true
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    scrubbing = false
                }
            })
        }
        playerPanel.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6f) }
        )

        val transport = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playPauseButton = Button(activity).apply {
            setText(R.string.media_pool_play)
            setTextColor(textPrimary)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            background = pillDrawable()
            setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
            setOnClickListener {
                if (videoView.isPlaying) videoView.pause() else videoView.start()
                setText(if (videoView.isPlaying) R.string.media_pool_pause else R.string.media_pool_play)
            }
        }
        transport.addView(playPauseButton)
        timecodeView = TextView(activity).apply {
            text = "00:00 / 00:00"
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(textDim)
        }
        transport.addView(
            timecodeView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12f) }
        )
        playerPanel.addView(
            transport,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4f) }
        )

        videoView.setOnPreparedListener { mp ->
            seekBar.max = mp.duration.coerceAtLeast(0)
            videoView.start()
            playPauseButton.setText(R.string.media_pool_pause)
            uiHandler.removeCallbacks(updateTick)
            uiHandler.post(updateTick)
        }
        videoView.setOnCompletionListener {
            playPauseButton.setText(R.string.media_pool_play)
        }
        videoView.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "playback failed what=$what extra=$extra (${currentClip?.name})")
            Toast.makeText(activity, "PLAYBACK ERROR", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun playClip(info: StorageHelper.ClipInfo) {
        val uri = info.uri
        if (uri == null) {
            Log.w(TAG, "clip has no playback uri: ${info.name}")
            Toast.makeText(activity, "CLIP UNAVAILABLE", Toast.LENGTH_SHORT).show()
            return
        }
        currentClip = info
        playerPanel.visibility = View.VISIBLE
        seekBar.progress = 0
        timecodeView.text = "00:00 / 00:00"
        videoView.setVideoURI(uri)
    }

    private fun stopAndHidePlayer() {
        runCatching { videoView.stopPlayback() }
            .onFailure { Log.w(TAG, "stopPlayback failed", it) }
        playerPanel.visibility = View.GONE
        currentClip = null
    }

    // -----------------------------------------------------------------------------------------
    // Clip list
    // -----------------------------------------------------------------------------------------

    private fun buildList() {
        listView = ListView(activity).apply {
            adapter = ClipsAdapter()
            divider = null
            dividerHeight = 0
            val pad = (8f * density + 0.5f).toInt()
            setPadding(pad, 0, pad, pad)
            cacheColorHint = 0
        }
    }

    private fun refresh() {
        clips = storage.listClips()
        (listView.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private inner class ClipsAdapter : BaseAdapter() {
        override fun getCount(): Int = if (clips.isEmpty()) 1 else clips.size
        override fun getItem(position: Int): Any? = clips.getOrNull(position)
        override fun getItemId(position: Int): Long =
            clips.getOrNull(position)?.name?.hashCode()?.toLong() ?: position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val info = clips.getOrNull(position)
            if (info == null) return emptyRow()
            val holder = (convertView?.tag as? RowHolder) ?: buildRow()
            bindRow(holder, info)
            return holder.row
        }
    }

    /** Single-row empty state: media_pool_empty hint. */
    private fun emptyRow(): View = TextView(activity).apply {
        text = activity.getString(R.string.media_pool_empty)
        setTextColor(textDim)
        textSize = 13f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        setPadding(0, (32f * density + 0.5f).toInt(), 0, 0)
    }

    private class RowHolder(
        val row: LinearLayout,
        val nameLabel: TextView,
        val gcsvBadge: TextView,
        val slateBadge: TextView,
        val gcsvChip: TextView,
        val goodTakeChip: TextView,
        var info: StorageHelper.ClipInfo? = null
    )

    private fun buildRow(): RowHolder {
        fun dp(v: Float): Int = (v * density + 0.5f).toInt()

        val nameLabel = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(textPrimary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val gcsvBadge = badge("GCSV")
        val slateBadge = badge("SLATE")
        val line1 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                nameLabel,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(gcsvBadge)
            addView(slateBadge)
        }

        val playChip = chip("\u25B6 " + activity.getString(R.string.media_pool_play))
        val slateChip = chip(activity.getString(R.string.media_pool_slate))
        val shareChip = chip(activity.getString(R.string.media_pool_share))
        val gcsvChip = chip("GCSV")
        val goodTakeChip = chip(activity.getString(R.string.media_pool_good_take))
        val deleteChip = chip(activity.getString(R.string.media_pool_delete))

        val holder = RowHolder(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(line1)
            },
            nameLabel, gcsvBadge, slateBadge, gcsvChip, goodTakeChip
        )

        val chipsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(playChip)
            addView(slateChip)
            addView(shareChip)
            addView(gcsvChip)
            addView(goodTakeChip)
            addView(deleteChip)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
        }
        holder.row.addView(chipsRow)

        playChip.setOnClickListener { holder.info?.let(::playClip) }
        slateChip.setOnClickListener { holder.info?.let(::showSlate) }
        shareChip.setOnClickListener { holder.info?.let(::shareClip) }
        gcsvChip.setOnClickListener { holder.info?.let(::shareGcsv) }
        goodTakeChip.setOnClickListener { holder.info?.let(::toggleGoodTake) }
        deleteChip.setOnClickListener { holder.info?.let(::confirmDelete) }
        holder.row.setOnClickListener { holder.info?.let(::playClip) }
        holder.row.background = flatDrawable(panelBg, 10f)
        holder.row.setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        holder.row.tag = holder
        return holder
    }

    private fun bindRow(holder: RowHolder, info: StorageHelper.ClipInfo) {
        holder.info = info
        holder.nameLabel.text = buildString {
            append(info.name)
            append("  \u00B7  ")
            append(String.format(Locale.US, "%.1f MB", info.sizeBytes / 1e6))
            if (info.widthPx > 0 && info.heightPx > 0) {
                append("  \u00B7  ")
                append(info.widthPx)
                append("x")
                append(info.heightPx)
            }
        }
        holder.gcsvBadge.setTextColor(if (info.hasGcsv) accent else textDim)
        holder.slateBadge.setTextColor(if (info.hasSlate) accent else textDim)
        holder.gcsvChip.visibility = if (info.hasGcsv) View.VISIBLE else View.GONE
        val good = goodTakeOf(info)
        holder.goodTakeChip.background =
            if (good) flatDrawable(accent, 16f) else pillDrawable()
        holder.goodTakeChip.setTextColor(if (good) 0xFF0A0C10.toInt() else textPrimary)
    }

    // -----------------------------------------------------------------------------------------
    // Row actions
    // -----------------------------------------------------------------------------------------

    private fun showSlate(info: StorageHelper.ClipInfo) {
        val body = TextView(activity).apply {
            text = storage.readSlateJson(info) ?: "no slate"
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(textPrimary)
            setTextIsSelectable(true)
            setPadding(
                (16f * density + 0.5f).toInt(),
                (12f * density + 0.5f).toInt(),
                (16f * density + 0.5f).toInt(),
                (12f * density + 0.5f).toInt()
            )
        }
        val scroll = ScrollView(activity).apply {
            addView(
                body,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
            .setTitle("${info.name}_slate.json")
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
            .window?.setLayout((460f * density + 0.5f).toInt(), (560f * density + 0.5f).toInt())
    }

    private fun shareClip(info: StorageHelper.ClipInfo) {
        val uri = info.uri ?: run {
            Log.w(TAG, "share requested but clip has no uri: ${info.name}")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (uri.scheme != "file") addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivity(Intent.createChooser(intent, "${info.name}.mp4")) }
            .onFailure { Log.w(TAG, "share chooser failed", it) }
    }

    private fun shareGcsv(info: StorageHelper.ClipInfo) {
        val uri: Uri = info.gcsvUri
            ?: info.gcsvFile?.let { Uri.fromFile(it) }
            ?: run {
                Log.w(TAG, "GCSV share requested but no telemetry file: ${info.name}")
                return
            }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            if (uri.scheme != "file") addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivity(Intent.createChooser(intent, "${info.name}.gcsv")) }
            .onFailure { Log.w(TAG, "gcsv share chooser failed", it) }
    }

    private fun toggleGoodTake(info: StorageHelper.ClipInfo) {
        val next = !goodTakeOf(info)
        if (storage.writeGoodTake(info, next)) {
            goodTakeCache[info.name] = next
            Toast.makeText(
                activity,
                activity.getString(R.string.media_pool_good_take) + if (next) " ON" else " OFF",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Log.w(TAG, "goodTake write failed for ${info.name}")
            Toast.makeText(activity, "SLATE UNAVAILABLE", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun confirmDelete(info: StorageHelper.ClipInfo) {
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
            .setMessage(activity.getString(R.string.media_pool_delete_confirm))
            .setPositiveButton(activity.getString(R.string.media_pool_delete)) { d, _ ->
                d.dismiss()
                val ok = storage.deleteClip(info)
                if (currentClip?.name == info.name) stopAndHidePlayer()
                goodTakeCache.remove(info.name)
                refresh()
                if (ok) {
                    Toast.makeText(activity, activity.getString(R.string.media_pool_deleted), Toast.LENGTH_SHORT).show()
                } else {
                    Log.w(TAG, "delete failed for ${info.name}")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /** Cached current goodTake value (slate JSON is ~400 B; parsed once per clip). */
    private fun goodTakeOf(info: StorageHelper.ClipInfo): Boolean {
        goodTakeCache[info.name]?.let { return it }
        val value = try {
            storage.readSlateJson(info)?.let { JSONObject(it).optBoolean("goodTake", false) } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "slate parse failed for ${info.name}", e)
            false
        }
        goodTakeCache[info.name] = value
        return value
    }

    private fun chip(label: String): TextView = TextView(activity).apply {
        text = label
        setTextColor(textPrimary)
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        background = pillDrawable()
        val padH = (10f * density + 0.5f).toInt()
        val padV = (5f * density + 0.5f).toInt()
        setPadding(padH, padV, padH, padV)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = (6f * density + 0.5f).toInt() }
    }

    private fun badge(label: String): TextView = TextView(activity).apply {
        text = label
        setTextColor(textDim)
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = (8f * density + 0.5f).toInt() }
    }

    private fun pillDrawable(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16f * density
        setColor(pillBgColor)
    }

    private fun flatDrawable(color: Int, cornerDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = cornerDp * density
        setColor(color)
    }
}
