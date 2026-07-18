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
                    // Try to trigger flash using AE mode
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
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
