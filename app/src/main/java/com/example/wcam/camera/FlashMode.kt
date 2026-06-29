package com.example.wcam.camera

import android.graphics.Color
import android.hardware.camera2.CaptureRequest

enum class FlashMode(
    val label: String,
    val textColor: Int,
) {
    OFF("关闭", Color.WHITE),
    SINGLE("打开", Color.parseColor("#FFCC00")),
    TORCH("常亮", Color.parseColor("#FFCC00"));

    fun applyToRequest(builder: CaptureRequest.Builder, isStillCapture: Boolean) {
        when (this) {
            OFF -> builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            SINGLE -> {
                if (isStillCapture) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                } else {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
            TORCH -> builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        }
    }
}
