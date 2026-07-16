package com.example.wcam.ui

import android.graphics.Color
import android.view.View
import android.widget.TextView
import com.example.wcam.camera.FlashMode

/**
 * Controller for top bar UI elements and mode switching.
 */
class CameraUiController(
    private val listener: UiEventListener
) {

    interface UiEventListener {
        fun onFlashModeChanged(mode: FlashMode)
        fun onHistogramToggle()
        fun onGridToggle()
        fun onResolutionClick(view: View)
        fun onFormatPanelToggle()
        fun onModeSwitch()
        fun onModeReset()
    }

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

    fun updateFormatButtonUi(btnFormat: TextView, isOpen: Boolean) {
        btnFormat.setTextColor(if (isOpen) Color.parseColor("#FFCC00") else Color.WHITE)
    }

    fun updateResolutionText(btnResolution: TextView, text: String, isChanged: Boolean = false) {
        btnResolution.text = text
        if (isChanged) {
            btnResolution.setTextColor(Color.parseColor("#FFCC00"))
        } else {
            btnResolution.setTextColor(Color.WHITE)
        }
    }
}
