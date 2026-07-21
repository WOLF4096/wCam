package com.example.wcam.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ListPopupWindow
import com.example.wcam.R
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
        fun onTimerClick(view: View)
        fun onTimerPanelDismissed()
        fun onGridToggle()
        fun onResolutionClick(view: View)
        fun onModeSwitch()
        fun onModeReset()
    }

    private var countdownTimer: android.os.CountDownTimer? = null

    fun updateFlashUi(btnFlash: ImageButton, mode: FlashMode) {
        btnFlash.setImageResource(mode.iconRes)
        if (mode != FlashMode.OFF) {
            btnFlash.setColorFilter(Color.parseColor("#FFCC00"))
        } else {
            btnFlash.clearColorFilter()
        }
    }

    fun updateTimerUi(btnTimer: ImageButton, hasActiveTimer: Boolean) {
        if (hasActiveTimer) {
            btnTimer.setColorFilter(Color.parseColor("#FFCC00"))
        } else {
            btnTimer.clearColorFilter()
        }
    }

    fun showTimerPanel(anchor: View, preferenceManager: com.example.wcam.PreferenceManager) {
        val popupView = activity.layoutInflater.inflate(R.layout.layout_timer_panel, null)
        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popupWindow.elevation = 10f

        val seekbarCountdown = popupView.findViewById<SeekBar>(R.id.seekbar_countdown)
        val tvCountdownVal = popupView.findViewById<TextView>(R.id.tv_countdown_val)
        val segAutoNone = popupView.findViewById<TextView>(R.id.seg_auto_none)
        val segAutoInterval = popupView.findViewById<TextView>(R.id.seg_auto_interval)
        val segAutoContinuous = popupView.findViewById<TextView>(R.id.seg_auto_continuous)
        val panelInterval = popupView.findViewById<View>(R.id.panel_auto_interval)
        val panelContinuous = popupView.findViewById<View>(R.id.panel_auto_continuous)
        val etIntervalSec = popupView.findViewById<EditText>(R.id.et_interval_sec)
        val etIntervalDuration = popupView.findViewById<EditText>(R.id.et_interval_duration)
        val etContinuousCount = popupView.findViewById<EditText>(R.id.et_continuous_count)
        val panelBlackScreen = popupView.findViewById<View>(R.id.panel_auto_black_screen)
        val switchBlackScreen = popupView.findViewById<Switch>(R.id.switch_auto_black_screen)

        val cdX10 = preferenceManager.getInt("countdown_val_x10", 0)
        seekbarCountdown.progress = cdX10
        tvCountdownVal.text = if (cdX10 % 10 == 0) "${cdX10 / 10}s" else String.format(Locale.US, "%.1fs", cdX10 / 10f)

        val autoMode = preferenceManager.getString("auto_capture_mode", "None")
        updateAutoModeSelection(autoMode, segAutoNone, segAutoInterval, segAutoContinuous, panelInterval, panelContinuous, panelBlackScreen)
        
        etIntervalSec.setText(preferenceManager.getString("auto_interval_sec", "5"))
        etIntervalDuration.setText(preferenceManager.getString("auto_interval_duration", "60"))
        etContinuousCount.setText(preferenceManager.getString("auto_continuous_count", "10"))
        switchBlackScreen.isChecked = preferenceManager.getBoolean(com.example.wcam.PreferenceManager.KEY_AUTO_BLACK_SCREEN, false)

        seekbarCountdown.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvCountdownVal.text = if (progress % 10 == 0) "${progress / 10}s" else String.format(Locale.US, "%.1fs", progress / 10f)
                if (fromUser) {
                    preferenceManager.saveInt("countdown_val_x10", progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        segAutoNone.setOnClickListener {
            preferenceManager.saveString("auto_capture_mode", "None")
            updateAutoModeSelection("None", segAutoNone, segAutoInterval, segAutoContinuous, panelInterval, panelContinuous, panelBlackScreen)
        }
        segAutoInterval.setOnClickListener {
            preferenceManager.saveString("auto_capture_mode", "Interval")
            updateAutoModeSelection("Interval", segAutoNone, segAutoInterval, segAutoContinuous, panelInterval, panelContinuous, panelBlackScreen)
        }
        segAutoContinuous.setOnClickListener {
            preferenceManager.saveString("auto_capture_mode", "Continuous")
            updateAutoModeSelection("Continuous", segAutoNone, segAutoInterval, segAutoContinuous, panelInterval, panelContinuous, panelBlackScreen)
        }

        switchBlackScreen.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.saveBoolean(com.example.wcam.PreferenceManager.KEY_AUTO_BLACK_SCREEN, isChecked)
        }

        etIntervalSec.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { preferenceManager.saveString("auto_interval_sec", s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        etIntervalDuration.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { preferenceManager.saveString("auto_interval_duration", s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        etContinuousCount.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { preferenceManager.saveString("auto_continuous_count", s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        popupWindow.setOnDismissListener { listener.onTimerPanelDismissed() }
        popupWindow.showAsDropDown(anchor, 0, 10)
    }

    private fun updateAutoModeSelection(mode: String, none: TextView, interval: TextView, continuous: TextView, panelI: View, panelC: View, panelB: View) {
        none.isSelected = mode == "None"
        interval.isSelected = mode == "Interval"
        continuous.isSelected = mode == "Continuous"
        panelI.visibility = if (mode == "Interval") View.VISIBLE else View.GONE
        panelC.visibility = if (mode == "Continuous") View.VISIBLE else View.GONE
        panelB.visibility = if (mode != "None") View.VISIBLE else View.GONE
    }

    fun showRatioPanel(anchor: View, options: List<String>, currentLabel: String, showCustom: Boolean, onSelected: (String) -> Unit) {
        val popupView = activity.layoutInflater.inflate(R.layout.layout_ratio_panel, null)
        val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popupWindow.elevation = 20f

        val grid = popupView.findViewById<GridLayout>(R.id.grid_ratios)
        grid.removeAllViews()

        var isFixedSelected = false
        options.forEach { option ->
            val chip = activity.layoutInflater.inflate(R.layout.item_ratio_chip, grid, false) as TextView
            chip.text = option
            val isSelected = (option == currentLabel)
            chip.isSelected = isSelected
            if (isSelected) isFixedSelected = true
            
            chip.setOnClickListener {
                onSelected(option)
                popupWindow.dismiss()
            }
            grid.addView(chip)
        }

        if (showCustom) {
            val customView = activity.layoutInflater.inflate(R.layout.item_ratio_custom, grid, false)
            val etW = customView.findViewById<EditText>(R.id.et_custom_w)
            val etH = customView.findViewById<EditText>(R.id.et_custom_h)
            
            if (!isFixedSelected && currentLabel.contains(":")) {
                customView.isSelected = true
                val parts = currentLabel.split(":")
                if (parts.size == 2) {
                    etW.setText(parts[0]); etH.setText(parts[1])
                }
            }

            customView.setOnClickListener {
                val w = etW.text.toString(); val h = etH.text.toString()
                if (w.isNotEmpty() && h.isNotEmpty()) {
                    onSelected("$w:$h"); popupWindow.dismiss()
                } else {
                    etW.requestFocus()
                    val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(etW, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }

            val editorListener = TextView.OnEditorActionListener { _, _, _ ->
                val w = etW.text.toString(); val h = etH.text.toString()
                if (w.isNotEmpty() && h.isNotEmpty()) {
                    onSelected("$w:$h"); popupWindow.dismiss(); true
                } else false
            }
            etW.setOnEditorActionListener(editorListener)
            etH.setOnEditorActionListener(editorListener)

            val textWatcher = object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    val w = etW.text.toString(); val h = etH.text.toString()
                    if (w.isNotEmpty() && h.isNotEmpty()) {
                        onSelected("$w:$h")
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            }
            etW.addTextChangedListener(textWatcher)
            etH.addTextChangedListener(textWatcher)

            grid.addView(customView)
        }

        popupWindow.showAsDropDown(anchor, 0, 10, android.view.Gravity.END)
    }

    fun updateGridUi(btnGrid: ImageButton, isVisible: Boolean) {
        if (isVisible) btnGrid.setColorFilter(Color.parseColor("#FFCC00"))
        else btnGrid.clearColorFilter()
    }

    fun updateResolutionText(btnResolution: TextView, text: String, isChanged: Boolean = false) {
        btnResolution.text = text
        btnResolution.setTextColor(if (isChanged) Color.parseColor("#FFCC00") else Color.WHITE)
    }

    fun startShutterCountdown(shutterNanos: Long, pbShutterProgress: ProgressBar, tvShutterCountdown: TextView) {
        val durationMs = shutterNanos / 1_000_000L
        if (durationMs < 500) return
        pbShutterProgress.visibility = View.VISIBLE; tvShutterCountdown.visibility = View.VISIBLE
        pbShutterProgress.progress = 0; tvShutterCountdown.text = String.format(Locale.US, "%.1fs", durationMs / 1000.0)
        countdownTimer?.cancel()
        countdownTimer = object : android.os.CountDownTimer(durationMs, 50) {
            override fun onTick(millisUntilFinished: Long) {
                val elapsed = durationMs - millisUntilFinished
                pbShutterProgress.progress = (elapsed.toFloat() / durationMs * 1000).toInt()
                tvShutterCountdown.text = String.format(Locale.US, "%.1fs", millisUntilFinished / 1000.0)
            }
            override fun onFinish() { pbShutterProgress.progress = 1000; tvShutterCountdown.text = "0.0s" }
        }.start()
    }

    fun stopShutterCountdown(pbShutterProgress: ProgressBar, tvShutterCountdown: TextView) {
        countdownTimer?.cancel(); countdownTimer = null
        pbShutterProgress.visibility = View.GONE; tvShutterCountdown.visibility = View.GONE
    }

    fun showStyledPopup(anchor: View, options: List<String>, onSelected: (String) -> Unit) {
        val listPopupWindow = ListPopupWindow(activity)
        listPopupWindow.anchorView = anchor
        val adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val layout = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(32, 24, 32, 24); setBackgroundColor(Color.parseColor("#CC1A1A1A"))
                }
                layout.removeAllViews()
                val textView = TextView(context).apply {
                    text = options[position]; setTextColor(Color.WHITE); textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                layout.addView(textView)
                return layout
            }
        }
        listPopupWindow.setAdapter(adapter)
        listPopupWindow.setBackgroundDrawable(ColorDrawable(Color.parseColor("#CC1A1A1A")))
        listPopupWindow.setOnItemClickListener { _, _, position, _ -> onSelected(options[position]); listPopupWindow.dismiss() }
        listPopupWindow.width = 960; listPopupWindow.show()
    }
}
