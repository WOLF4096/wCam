package com.example.wcam.camera

import android.graphics.Color
import android.hardware.camera2.CaptureRequest
import com.example.wcam.R

enum class FlashMode(val iconRes: Int) {
    OFF(R.drawable.ic_flash_off),
    SINGLE(R.drawable.ic_flash_on),
    TORCH(R.drawable.ic_flash_torch);

    fun applyToRequest(builder: CaptureRequest.Builder, isStillCapture: Boolean) {
        when (this) {
            OFF -> {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            SINGLE -> {
                if (isStillCapture) {
                    // 只管灯，不管曝光模式。由 PhotoParamMapper 决定 AE_MODE。
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
            TORCH -> {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }
        }
    }
}
