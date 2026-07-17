package com.example.wcam.ui

import android.view.View
import android.widget.TextView
import com.example.wcam.GridLineView
import com.example.wcam.CropOverlayView

/**
 * Manages the visibility and updates of various overlays.
 */
class OverlayController(
    private val gridLines: GridLineView,
    private val histogram: HistogramView,
    private val cropOverlay: CropOverlayView,
    private val physicalRes: TextView
) {

    fun setGridVisible(visible: Boolean) {
        gridLines.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setHistogramVisible(visible: Boolean) {
        histogram.visibility = if (visible) View.VISIBLE else View.GONE
        physicalRes.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setHistogramSize(widthDp: Int, heightDp: Int) {
        val density = histogram.context.resources.displayMetrics.density
        val params = histogram.layoutParams
        params.width = (widthDp * density).toInt()
        params.height = (heightDp * density).toInt()
        histogram.layoutParams = params
    }

    fun setCropOverlayVisible(visible: Boolean) {
        cropOverlay.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun updateHistogram(r: IntArray, g: IntArray, b: IntArray) {
        histogram.updateData(r, g, b)
    }

    fun updateCrop(relLeft: Float, relTop: Float, relRight: Float, relBottom: Float) {
        cropOverlay.setCropRect(relLeft, relTop, relRight, relBottom)
    }

    fun updatePhysicalResText(text: String) {
        physicalRes.text = text
    }
}
