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
                // 使用 TORCH 模式脉冲来实现“打开”逻辑，以确保在手动曝光下也能强制开启
                if (isStillCapture) {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
            TORCH -> builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        }
    }
}
