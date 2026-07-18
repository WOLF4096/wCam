package com.example.wcam.ui

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ListPopupWindow
import com.example.wcam.camera.FlashMode
import java.util.Locale

/**
 * Controller for top bar UI elements and mode switching.
 */
class CameraUiController(
    private val activity: AppCompatActivity,
    private val listener: UiEventListener
) {

    interface UiEventListener {
        fun onFlashModeChanged(mode: FlashMode)
        fun onHistogramToggle()
        fun onGridToggle()
        fun onResolutionClick(view: View)
        fun onModeSwitch()
        fun onModeReset()
    }

    private var countdownTimer: android.os.CountDownTimer? = null

    fun updateFlashUi(btnFlash: TextView, mode: FlashMode) {
        btnFlash.text = mode.label
        btnFlash.setTextColor(mode.textColor)
    }

    fun updateHistogramUi(btnHistogram: TextView, isVisible: Boolean) {
        btnHistogram.setTextColor(if (isVisible) Color.parseColor("#FFCC00") else Color.WHITE)
    }

    fun updateGridUi(btnGrid: TextView, isVisible: Boolean) {
        btnGrid.setTextColor(if (isVisible) Color.parseColor("#FFCC00") else Color.WHITE)
    }

    fun updateResolutionText(btnResolution: TextView, text: String, isChanged: Boolean = false) {
        btnResolution.text = text
        if (isChanged) {
            btnResolution.setTextColor(Color.parseColor("#FFCC00"))
        } else {
            btnResolution.setTextColor(Color.WHITE)
        }
    }

    fun startShutterCountdown(shutterNanos: Long, pbShutterProgress: ProgressBar, tvShutterCountdown: TextView) {
        val durationMs = shutterNanos / 1_000_000L
        if (durationMs < 500) return

        pbShutterProgress.visibility = View.VISIBLE
        tvShutterCountdown.visibility = View.VISIBLE
        pbShutterProgress.progress = 0
        tvShutterCountdown.text = String.format(Locale.US, "%.1fs", durationMs / 1000.0)

        countdownTimer?.cancel()
        countdownTimer = object : android.os.CountDownTimer(durationMs, 50) {
            override fun onTick(millisUntilFinished: Long) {
                val elapsed = durationMs - millisUntilFinished
                val progress = (elapsed.toFloat() / durationMs * 1000).toInt()
                pbShutterProgress.progress = progress
                tvShutterCountdown.text = String.format(Locale.US, "%.1fs", millisUntilFinished / 1000.0)
            }

            override fun onFinish() {
                pbShutterProgress.progress = 1000
                tvShutterCountdown.text = "0.0s"
            }
        }.start()
    }

    fun stopShutterCountdown(pbShutterProgress: ProgressBar, tvShutterCountdown: TextView) {
        countdownTimer?.cancel()
        countdownTimer = null
        pbShutterProgress.visibility = View.GONE
        tvShutterCountdown.visibility = View.GONE
    }

    fun showStyledPopup(anchor: View, options: List<String>, onSelected: (String) -> Unit) {
        val listPopupWindow = ListPopupWindow(activity)
        listPopupWindow.animationStyle = 0
        listPopupWindow.anchorView = anchor
        val adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val layout = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(32, 24, 32, 24)
                    setBackgroundColor(Color.parseColor("#CC1A1A1A"))
                }
                layout.removeAllViews()

                val ratioStr = options[position]
                if (ratioStr.contains(":")) {
                    val ratioView = View(context).apply {
                        val parts = ratioStr.split(":")
                        val w = parts[0].toFloatOrNull() ?: 1f
                        val h = parts[1].toFloatOrNull() ?: 1f
                        val baseSize = 40f
                        val finalW: Float
                        val finalH: Float
                        if (w > h) {
                            finalW = baseSize
                            finalH = baseSize * (h / w)
                        } else {
                            finalH = baseSize
                            finalW = baseSize * (w / h)
                        }
                        layoutParams = LinearLayout.LayoutParams(finalW.toInt(), finalH.toInt()).apply {
                            marginEnd = 24
                        }
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setStroke(2, Color.WHITE)
                            setColor(Color.TRANSPARENT)
                        }
                    }
                    layout.addView(ratioView)
                }

                val textView = TextView(context).apply {
                    text = ratioStr
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                layout.addView(textView)
                return layout
            }
        }
        listPopupWindow.setAdapter(adapter)
        listPopupWindow.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#CC1A1A1A")))
        listPopupWindow.setOnItemClickListener { _, _, position, _ ->
            onSelected(options[position])
            listPopupWindow.dismiss()
        }
        listPopupWindow.width = 960
        listPopupWindow.show()
    }
}
