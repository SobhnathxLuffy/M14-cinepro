package com.m14.cinepro.hud

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.m14.cinepro.R
import com.m14.cinepro.core.SettingsStore

/**
 * M14 CinePro — cinema slate dialog (reel / scene / take / director / operator / good-take).
 *
 * Pure framework [AlertDialog] with a fully programmatic dark form. On Save the fields are
 * written through [SettingsStore] (which persists them and feeds the clip metadata registry
 * via RecordingController's SlateManager); [onSave] lets the caller refresh the HUD.
 */
object SlateDialog {

    fun show(activity: Activity, settings: SettingsStore, onSave: () -> Unit) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density + 0.5f).toInt()

        val textDark = activity.getColor(R.color.hud_text_primary)
        val textDim = activity.getColor(R.color.hud_text_dim)
        val fieldBgColor = 0xFF1A1E26.toInt()

        fun newFieldBg(): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10f).toFloat()
            setColor(fieldBgColor)
        }

        fun caption(text: String): TextView = TextView(activity).apply {
            this.text = text
            setTextColor(textDim)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setAllCaps(true)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(10f)
            lp.bottomMargin = dp(2f)
            layoutParams = lp
        }

        fun field(value: String, inputType: Int, hint: String): EditText =
            EditText(activity).apply {
                setText(value)
                this.hint = hint
                setHintTextColor(textDim)
                setTextColor(textDark)
                textSize = 15f
                this.inputType = inputType
                isSingleLine = true
                background = newFieldBg()
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20f)
            setPadding(pad, dp(8f), pad, 0)
        }

        val summary = TextView(activity).apply {
            text = settings.describeForMetadata()
            setTextColor(textDim)
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        container.addView(summary)

        container.addView(caption(activity.getString(R.string.slate_reel)))
        val reelEt = field(settings.reel.toString(), InputType.TYPE_CLASS_NUMBER, "001")
        container.addView(reelEt)

        container.addView(caption(activity.getString(R.string.slate_scene)))
        val sceneEt = field(settings.scene, InputType.TYPE_CLASS_TEXT, "12A")
        container.addView(sceneEt)

        container.addView(caption(activity.getString(R.string.slate_take)))
        val takeEt = field(settings.take.toString(), InputType.TYPE_CLASS_NUMBER, "1")
        container.addView(takeEt)

        container.addView(caption(activity.getString(R.string.slate_director)))
        val directorEt = field(
            settings.director,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
            activity.getString(R.string.slate_director)
        )
        container.addView(directorEt)

        container.addView(caption(activity.getString(R.string.slate_operator)))
        val operatorEt = field(
            settings.operator,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
            activity.getString(R.string.slate_operator)
        )
        container.addView(operatorEt)

        val goodTake = CheckBox(activity).apply {
            text = activity.getString(R.string.slate_good_take)
            setTextColor(textDark)
            textSize = 14f
            isChecked = settings.goodTake
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(14f)
            layoutParams = lp
        }
        container.addView(goodTake)

        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(
                container,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog)
            .setTitle(activity.getString(R.string.slate_title))
            .setView(scroll)
            .setPositiveButton(activity.getString(R.string.slate_ok)) { dialog, _ ->
                settings.reel = reelEt.text.toString().trim().toIntOrNull()?.coerceAtLeast(1)
                    ?: settings.reel
                val scene = sceneEt.text.toString().trim()
                if (scene.isNotEmpty()) settings.scene = scene
                settings.take = takeEt.text.toString().trim().toIntOrNull()?.coerceAtLeast(1)
                    ?: settings.take
                settings.goodTake = goodTake.isChecked
                settings.director = directorEt.text.toString().trim()
                settings.operator = operatorEt.text.toString().trim()
                onSave()
                dialog.dismiss()
            }
            .setNegativeButton(activity.getString(R.string.slate_cancel), null)
            .show()
            .window?.let { w ->
                w.setLayout(dp(420f), ViewGroup.LayoutParams.WRAP_CONTENT)
                w.setGravity(Gravity.CENTER)
            }
    }
}
