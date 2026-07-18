package com.example.wcam.camera

import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import java.text.SimpleDateFormat
import java.util.*

class WatermarkProcessor {

    data class WatermarkConfig(
        val type: String, // "None", "Internal", "External"
        val showTime: Boolean,
        val showParams: Boolean,
        val showText: Boolean,
        val customText: String
    )

    fun applyWatermark(
        bitmap: Bitmap,
        config: WatermarkConfig,
        characteristics: CameraCharacteristics?,
        result: TotalCaptureResult?
    ): Bitmap {
        if (config.type == "None") return bitmap

        val text = buildWatermarkText(config, result, characteristics)
        if (text.isEmpty()) return bitmap

        return if (config.type == "Internal") {
            drawInternalWatermark(bitmap, text)
        } else {
            drawExternalWatermark(bitmap, text)
        }
    }

    private fun buildWatermarkText(config: WatermarkConfig, result: TotalCaptureResult?, chars: CameraCharacteristics?): String {
        val parts = mutableListOf<String>()

        if (config.showTime) {
            parts.add(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        }

        if (config.showParams && result != null && chars != null) {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val aperture = result.get(CaptureResult.LENS_APERTURE) ?: chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
            
            val expStr = if (exp != null && exp > 0) {
                val seconds = exp / 1_000_000_000.0
                if (seconds >= 1.0) String.format("%.1fs", seconds) else "1/${(1.0 / seconds).toInt()}s"
            } else "N/A"
            
            parts.add("ISO$iso | $expStr | f/$aperture")
        }

        if (config.showText && config.customText.isNotEmpty()) {
            parts.add(config.customText)
        }

        return parts.joinToString(" | ")
    }

    private fun drawInternalWatermark(bitmap: Bitmap, text: String): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = bitmap.height / 50f
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }

        // Position: 100px from bottom-left
        canvas.drawText(text, 100f, bitmap.height - 100f, paint)
        return mutableBitmap
    }

    private fun drawExternalWatermark(bitmap: Bitmap, text: String): Bitmap {
        val watermarkHeight = 200 // Total height for external area
        val newHeight = bitmap.height + watermarkHeight
        val newBitmap = Bitmap.createBitmap(bitmap.width, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)
        
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = bitmap.height / 50f
        }

        // Position: 100px from the original bottom (start of external area)
        canvas.drawText(text, 100f, bitmap.height + 120f, paint)
        return newBitmap
    }
}
