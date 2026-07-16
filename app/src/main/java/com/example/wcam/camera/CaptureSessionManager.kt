package com.example.wcam.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executor

/**
 * Manages CameraCaptureSession creation and request handling.
 */
class CaptureSessionManager(
    private val listener: SessionListener
) {

    interface SessionListener {
        fun onSessionConfigured(session: CameraCaptureSession)
        fun onSessionConfigureFailed()
    }

    private var captureSession: CameraCaptureSession? = null

    fun createSession(
        camera: CameraDevice,
        surfaces: List<Surface>,
        physicalCameraId: String?,
        initialRequest: CaptureRequest,
        backgroundHandler: Handler,
        rawSurface: Surface? = null // For special OutputConfig
    ) {
        try {
            captureSession?.close()

            val previewStateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    listener.onSessionConfigured(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    listener.onSessionConfigureFailed()
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfigs = surfaces.map { s ->
                    OutputConfiguration(s).apply {
                        physicalCameraId?.let { setPhysicalCameraId(it) }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && s == rawSurface) {
                            try {
                                streamUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE.toLong()
                            } catch (e: Exception) {
                                Log.w("CaptureSessionManager", "Failed to set StreamUseCase")
                            }
                        }
                    }
                }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    Executor { command -> backgroundHandler.post(command) },
                    previewStateCallback
                )
                sessionConfig.sessionParameters = initialRequest
                camera.createCaptureSession(sessionConfig)
            } else {
                camera.createCaptureSession(surfaces, previewStateCallback, backgroundHandler)
            }
        } catch (e: Exception) {
            Log.e("CaptureSessionManager", "Failed to create session", e)
        }
    }

    fun close() {
        captureSession?.close()
        captureSession = null
    }

    fun applyZoom(builder: CaptureRequest.Builder, zoomRatio: Float, characteristics: CameraCharacteristics?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        } else {
            val arrayRect = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (arrayRect != null) {
                val cropW = (arrayRect.width() / zoomRatio).toInt()
                val cropH = (arrayRect.height() / zoomRatio).toInt()
                val left = (arrayRect.width() - cropW) / 2
                val top = (arrayRect.height() - cropH) / 2
                builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropW, top + cropH))
            }
        }
    }
}
