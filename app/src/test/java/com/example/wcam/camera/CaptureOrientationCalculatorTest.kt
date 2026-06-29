package com.example.wcam.camera

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureOrientationCalculatorTest {
    @Test
    fun nearestDeviceRotationDegrees_snapsToQuadrants() {
        assertEquals(0, CaptureOrientationCalculator.nearestDeviceRotationDegrees(359))
        assertEquals(90, CaptureOrientationCalculator.nearestDeviceRotationDegrees(46))
        assertEquals(180, CaptureOrientationCalculator.nearestDeviceRotationDegrees(179))
        assertEquals(270, CaptureOrientationCalculator.nearestDeviceRotationDegrees(260))
    }

    @Test
    fun captureRotation_backCameraSubtractsDeviceRotationFromSensorOrientation() {
        val rotation = CaptureOrientationCalculator.captureRotation(
            sensorOrientation = 90,
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            deviceRotationDegrees = 90,
        )

        assertEquals(0, rotation.degrees)
        assertEquals(EXIF_ORIENTATION_NORMAL, rotation.exifOrientation)
    }

    @Test
    fun captureRotation_frontCameraAddsDeviceRotationToSensorOrientation() {
        val rotation = CaptureOrientationCalculator.captureRotation(
            sensorOrientation = 90,
            lensFacing = CameraCharacteristics.LENS_FACING_FRONT,
            deviceRotationDegrees = 90,
        )

        assertEquals(180, rotation.degrees)
        assertEquals(EXIF_ORIENTATION_ROTATE_180, rotation.exifOrientation)
    }
}
