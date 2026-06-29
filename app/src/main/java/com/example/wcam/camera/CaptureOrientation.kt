package com.example.wcam.camera

import android.content.Context
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.view.OrientationEventListener

internal const val EXIF_ORIENTATION_NORMAL = 1
internal const val EXIF_ORIENTATION_ROTATE_90 = 6
internal const val EXIF_ORIENTATION_ROTATE_180 = 3
internal const val EXIF_ORIENTATION_ROTATE_270 = 8

data class CaptureRotation(
    val degrees: Int,
    val exifOrientation: Int,
)

object CaptureOrientationCalculator {
    fun nearestDeviceRotationDegrees(orientationDegrees: Int): Int {
        val normalized = ((orientationDegrees % 360) + 360) % 360
        return when {
            normalized >= 315 || normalized < 45 -> 0
            normalized < 135 -> 90
            normalized < 225 -> 180
            else -> 270
        }
    }

    fun captureRotation(
        sensorOrientation: Int,
        lensFacing: Int?,
        deviceRotationDegrees: Int,
    ): CaptureRotation {
        val normalizedDeviceRotation = nearestDeviceRotationDegrees(deviceRotationDegrees)
        val jpegDegrees = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + normalizedDeviceRotation) % 360
        } else {
            (sensorOrientation - normalizedDeviceRotation + 360) % 360
        }
        return CaptureRotation(
            degrees = jpegDegrees,
            exifOrientation = exifOrientationForDegrees(jpegDegrees),
        )
    }

    fun exifOrientationForDegrees(degrees: Int): Int {
        return when (nearestDeviceRotationDegrees(degrees)) {
            90 -> EXIF_ORIENTATION_ROTATE_90
            180 -> EXIF_ORIENTATION_ROTATE_180
            270 -> EXIF_ORIENTATION_ROTATE_270
            else -> EXIF_ORIENTATION_NORMAL
        }
    }
}

class DeviceOrientationTracker(context: Context) : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
    var deviceRotationDegrees: Int = 0
        private set

    override fun onOrientationChanged(orientation: Int) {
        if (orientation == ORIENTATION_UNKNOWN) {
            return
        }
        deviceRotationDegrees = CaptureOrientationCalculator.nearestDeviceRotationDegrees(orientation)
    }
}
