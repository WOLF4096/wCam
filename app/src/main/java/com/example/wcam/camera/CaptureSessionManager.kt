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
            Log.d("PerfLog", "createSession started")
            captureSession?.close()

            val previewStateCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    Log.d("PerfLog", "CameraCaptureSession.StateCallback.onConfigured")
                    captureSession = session
                    listener.onSessionConfigured(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.d("PerfLog", "CameraCaptureSession.StateCallback.onConfigureFailed")
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

    fun triggerFocusAndMetering(
        x: Float, y: Float,
        textureView: android.view.TextureView,
        characteristics: CameraCharacteristics?,
        previewRequestBuilder: CaptureRequest.Builder?,
        backgroundHandler: Handler?,
        callback: CameraCaptureSession.CaptureCallback
    ) {
        val sensorArraySize = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        
        val sensorX = (y / viewH * sensorArraySize.width()).toInt()
        val sensorY = ((1.0f - x / viewW) * sensorArraySize.height()).toInt()
        
        val halfRectSize = 100
        val focusRect = android.graphics.Rect(
            (sensorX - halfRectSize).coerceIn(0, sensorArraySize.width()),
            (sensorY - halfRectSize).coerceIn(0, sensorArraySize.height()),
            (sensorX + halfRectSize).coerceIn(0, sensorArraySize.width()),
            (sensorY + halfRectSize).coerceIn(0, sensorArraySize.height())
        )
        
        val meteringRect = android.hardware.camera2.params.MeteringRectangle(focusRect, 1000)
        
        previewRequestBuilder?.apply {
            set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
            set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
        }
        
        try {
            captureSession?.capture(previewRequestBuilder!!.build(), callback, backgroundHandler)
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toggleAeAfLock(locked: Boolean, previewRequestBuilder: CaptureRequest.Builder?) {
        previewRequestBuilder?.apply {
            if (locked) {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_EDOF)
                set(CaptureRequest.CONTROL_AE_LOCK, true)
            } else {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_LOCK, false)
            }
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
