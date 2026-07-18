package com.example.wcam.camera

import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
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
        result: TotalCaptureResult?,
        rotationDegrees: Int
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
                if (seconds >= 1.0) String.format(Locale.US, "%.1fs", seconds) 
                else "1/${(1.0 / seconds).toInt()}s"
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
        
        // Font size relative to height, but at least a reasonable absolute size
        val textSize = (bitmap.height / 40f).coerceAtLeast(40f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            setShadowLayer(3f, 2f, 2f, Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // Position: 100px from bottom-left (absolute pixels)
        // Since the bitmap is already rotated to its final orientation, bottom-left is always bottom-left.
        canvas.drawText(text, 100f, bitmap.height - 100f, paint)
        return mutableBitmap
    }

    private fun drawExternalWatermark(bitmap: Bitmap, text: String): Bitmap {
        val padding = 200 // Total height for external area
        val newBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height + padding, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)
        
        canvas.drawColor(Color.WHITE)
        // Draw original photo at top
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        val textSize = (bitmap.height / 40f).coerceAtLeast(40f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // Requirements say "bottom-left 100, -100px". 
        // This means it's 100px up from the bottom of the NEW image.
        canvas.drawText(text, 100f, newBitmap.height - 100f, paint)
        return newBitmap
    }
}
