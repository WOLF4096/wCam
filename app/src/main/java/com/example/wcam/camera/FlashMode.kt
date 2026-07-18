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
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            SINGLE -> {
                if (isStillCapture) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                } else {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
            }
            TORCH -> {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
        }
    }
}
