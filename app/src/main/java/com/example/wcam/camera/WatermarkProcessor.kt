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
        val showAlgo: Boolean,
        val showText: Boolean,
        val customText: String,
        val algoName: String = ""
    )

    fun applyWatermark(
        bitmap: Bitmap,
        config: WatermarkConfig,
        characteristics: CameraCharacteristics?,
        result: TotalCaptureResult?,
        rotationDegrees: Int, // Note: bitmap passed here should be ALREADY ROTATED
        configManager: com.example.wcam.util.ConfigManager
    ): Bitmap {
        if (config.type == "None") return bitmap

        val text = buildWatermarkText(config, result, characteristics)
        if (text.isEmpty()) return bitmap

        val wmStart = System.currentTimeMillis()
        val resultBitmap = if (config.type == "Internal") {
            drawInternalWatermark(bitmap, text, configManager)
        } else {
            drawExternalWatermark(bitmap, text, configManager)
        }
        android.util.Log.d("CaptureTimer", "Watermark drawing took ${System.currentTimeMillis() - wmStart}ms")
        return resultBitmap
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

        if (config.showAlgo && config.algoName.isNotEmpty()) {
            parts.add(config.algoName)
        }

        if (config.showText && config.customText.isNotEmpty()) {
            parts.add(config.customText)
        }

        return parts.joinToString(" | ")
    }

    private fun getTypeface(config: com.example.wcam.util.ConfigManager): Typeface {
        val family = config.getString("wm_font_family", "DEFAULT")
        val styleStr = config.getString("wm_font_style", "BOLD")
        
        val baseTypeface = when (family.uppercase(Locale.US)) {
            "MONOSPACE" -> Typeface.MONOSPACE
            "SERIF" -> Typeface.SERIF
            "SANS_SERIF" -> Typeface.SANS_SERIF
            else -> Typeface.DEFAULT
        }
        
        val style = when (styleStr.uppercase(Locale.US)) {
            "BOLD" -> Typeface.BOLD
            "ITALIC" -> Typeface.ITALIC
            "BOLD_ITALIC" -> Typeface.BOLD_ITALIC
            else -> Typeface.NORMAL
        }
        
        return Typeface.create(baseTypeface, style)
    }

    private fun drawInternalWatermark(bitmap: Bitmap, text: String, config: com.example.wcam.util.ConfigManager): Bitmap {
        val configToUse = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.RGBA_F16) {
            Bitmap.Config.RGBA_F16
        } else {
            Bitmap.Config.ARGB_8888
        }
        val mutableBitmap = bitmap.copy(configToUse, true)
        val canvas = Canvas(mutableBitmap)
        
        val baseFontSize = config.getFloat("wm_font_size", 64f)
        val basePaddingX = config.getFloat("wm_padding_x", 40f)
        val basePaddingY = config.getFloat("wm_padding_y", 40f)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = baseFontSize
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            typeface = getTypeface(config)
        }

        // Calculate text width and scale if necessary
        val textWidth = paint.measureText(text)
        val requiredWidth = textWidth + basePaddingX * 2
        val scale = if (requiredWidth > bitmap.width) {
            bitmap.width.toFloat() / requiredWidth
        } else 1.0f

        val finalFontSize = baseFontSize * scale
        val finalPaddingX = basePaddingX * scale
        val finalPaddingY = basePaddingY * scale

        paint.textSize = finalFontSize

        // Draw at bottom left
        canvas.drawText(text, finalPaddingX, bitmap.height - finalPaddingY, paint)
        return mutableBitmap
    }

    private fun drawExternalWatermark(bitmap: Bitmap, text: String, config: com.example.wcam.util.ConfigManager): Bitmap {
        val baseFontSize = config.getFloat("wm_font_size", 64f)
        val basePaddingX = config.getFloat("wm_padding_x", 40f)
        val basePaddingY = config.getFloat("wm_padding_y", 40f)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.textSize = baseFontSize
            typeface = getTypeface(config)
        }

        // Calculate scale
        val textWidth = paint.measureText(text)
        val requiredWidth = textWidth + basePaddingX * 2
        val scale = if (requiredWidth > bitmap.width) {
            bitmap.width.toFloat() / requiredWidth
        } else 1.0f

        val finalFontSize = baseFontSize * scale
        val finalPaddingX = basePaddingX * scale
        val finalPaddingY = basePaddingY * scale
        
        paint.textSize = finalFontSize
        
        // Calculate white bar height based on scaled font height and vertical padding
        val fontMetrics = paint.fontMetrics
        val fontHeight = fontMetrics.descent - fontMetrics.ascent
        val externalPadding = (fontHeight + finalPaddingY * 2).toInt()

        val newHeight = bitmap.height + externalPadding
        val newBitmap = createCompatibleBitmap(bitmap.width, newHeight, bitmap)
        val canvas = Canvas(newBitmap)
        
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        // Draw in the middle of the white area vertically
        // The Y coordinate for drawText is the baseline. 
        // Middle of white bar is bitmap.height + externalPadding/2
        // We need to offset it to center the font height.
        val centerY = bitmap.height + (externalPadding / 2f) - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(text, finalPaddingX, centerY, paint)
        return newBitmap
    }

    private fun createCompatibleBitmap(width: Int, height: Int, sourceBitmap: Bitmap): Bitmap {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && sourceBitmap.config == Bitmap.Config.RGBA_F16) {
            Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16)
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }
}
