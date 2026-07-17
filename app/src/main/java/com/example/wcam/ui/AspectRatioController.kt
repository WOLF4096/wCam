package com.example.wcam.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.example.wcam.PreferenceManager
import java.util.Locale

/**
 * Controller for photo and video aspect ratios and crop logic.
 */
class AspectRatioController(
    private val context: Context,
    private val preferenceManager: PreferenceManager,
    private val overlayController: OverlayController,
    private val listener: AspectRatioListener
) {

    interface AspectRatioListener {
        fun onRatioChanged(ratio: Float, label: String)
        fun onVideoParamsChanged()
        fun showStyledPopup(anchor: View, options: List<String>, onSelected: (String) -> Unit)
    }

    private var cropRatio = 4f / 3f
    private var cropScale = 1.0f
    private var customRatioW = 4
    private var customRatioH = 3

    fun setCropScale(scale: Float) {
        cropScale = (cropScale * scale).coerceIn(0.1f, 1.0f)
    }

    fun updateCropUI(isPhotoMode: Boolean, activeChars: CameraCharacteristics?, selectedVideoSize: String, selectedVideoFps: Int): Rect? {
        val chars = activeChars ?: return null
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        
        val sensorW = sensorSize.width()
        val sensorH = sensorSize.height()
        val sensorRatio = sensorW.toFloat() / sensorH

        val maxW: Float
        val maxH: Float
        if (cropRatio > sensorRatio) {
            maxW = sensorW.toFloat()
            maxH = sensorW / cropRatio
        } else {
            maxH = sensorH.toFloat()
            maxW = sensorH * cropRatio
        }

        val currentW = maxW * cropScale
        val currentH = maxH * cropScale

        val mp = (currentW * currentH) / 1_000_000.0
        var resText = if (isPhotoMode) {
            "${currentW.toInt()}x${currentH.toInt()}@${String.format(Locale.US, "%.1f", mp)}MP"
        } else {
            "$selectedVideoSize@${selectedVideoFps}fps"
        }

        if (isPhotoMode) {
            val format = preferenceManager.getString(PreferenceManager.KEY_OUTPUT_FORMAT, "JPG")
            val quality = preferenceManager.getInt(PreferenceManager.KEY_JPG_QUALITY, 95)
            val fullAlgo = preferenceManager.getString(PreferenceManager.KEY_DEMOSAIC_ALGO, "Fast")
            val algo = fullAlgo.split('：', ':')[0].trim()
            
            val formatInfo = when (format) {
                "JPG" -> "JPG $quality%"
                "JPG + RAW" -> "JPG $quality% + RAW"
                "RAW" -> "RAW"
                else -> format
            }
            
            resText += " | $formatInfo"
            if (format != "RAW") {
                resText += " | $algo"
            }
        }

        overlayController.updatePhysicalResText(resText)

        val sensorLeft = (sensorW - currentW) / 2
        val sensorTop = (sensorH - currentH) / 2
        
        val viewRelW = currentH / sensorH
        val viewRelH = currentW / sensorW
        val relLeft = (1.0f - viewRelW) / 2
        val relTop = (1.0f - viewRelH) / 2
        
        overlayController.updateCrop(relLeft, relTop, relLeft + viewRelW, relTop + viewRelH)
        
        return Rect(sensorLeft.toInt(), sensorTop.toInt(), (sensorLeft + currentW).toInt(), (sensorTop + currentH).toInt())
    }

    fun showPhotoRatioPopup(anchor: View) {
        val options = listOf("1:1", "3:4", "9:16", "1:2", "自定义")
        listener.showStyledPopup(anchor, options) { selected ->
            when (selected) {
                "1:1" -> updateRatio(1.0f, "1:1")
                "3:4" -> updateRatio(4f / 3f, "3:4")
                "9:16" -> updateRatio(16f / 9f, "9:16")
                "1:2" -> updateRatio(2.0f, "1:2")
                "自定义" -> showCustomRatioDialog()
            }
        }
    }

    private fun updateRatio(ratio: Float, label: String) {
        cropRatio = ratio
        listener.onRatioChanged(ratio, label)
    }

    private fun showCustomRatioDialog() {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(50, 40, 50, 10)
        }
        val etW = android.widget.EditText(context).apply {
            hint = "宽"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setText(customRatioW.toString())
        }
        val etH = android.widget.EditText(context).apply {
            hint = "高"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setText(customRatioH.toString())
        }
        layout.addView(etW)
        layout.addView(etH)

        AlertDialog.Builder(context)
            .setTitle("自定义比例")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                val w = etW.text.toString().toIntOrNull() ?: 4
                val h = etH.text.toString().toIntOrNull() ?: 3
                if (w > 0 && h > 0) {
                    customRatioW = w
                    customRatioH = h
                    updateRatio(w.toFloat() / h, "$h:$w")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
