package com.example.wcam.camera

import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.camera2.*
import android.media.Image
import android.os.Handler
import android.util.Log
import android.view.Surface
import com.example.wcam.PreferenceManager
import com.example.wcam.ui.ManualParamController
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

/**
 * Orchestrates the high-level capture flow (RAW, JPG, burst matching).
 */
class CaptureManager(
    private val cameraManager: CameraManager,
    private val captureProcessor: CaptureProcessor,
    private val preferenceManager: PreferenceManager,
    private val captureExecutor: ExecutorService,
    private val backgroundHandler: Handler,
    private val listener: CaptureListener
) {

    interface CaptureListener {
        fun onCaptureStarted(shutterNanos: Long)
        fun onCaptureCompleted(uri: android.net.Uri?)
        fun onCaptureFailed(message: String)
        fun onReadyToResumePreview()
        fun getActiveCameraCharacteristics(): CameraCharacteristics?
        fun getPhotoParamState(): PhotoParamState
        fun getPreviewAutoValues(): ManualParamController.PreviewAutoValues
        fun getFlashMode(): FlashMode
        fun getZoomRatio(): Float
        fun getDeviceRotationDegrees(): Int
        fun getSensorOrientation(): Int
        fun getLensFacing(): Int?
        fun getCurrentCropRect(): Rect?
        fun getJpgQuality(): Int
        fun getCameraCapabilities(): CameraCapabilities
        fun getLocation(): android.location.Location?
    }

    private val pendingRawImages = ConcurrentHashMap<Long, Image>()
    private val pendingCaptureResults = ConcurrentHashMap<Long, TotalCaptureResult>()
    private val captureStartTimeMap = ConcurrentHashMap<Long, Long>()

    @Volatile
    var isCaptureInProgress = false
        private set

    fun capturePhoto(camera: CameraDevice, session: CameraCaptureSession, previewSurface: Surface?) {
        if (isCaptureInProgress) return
        
        val shutterNanos = calculateShutterNanos()
        isCaptureInProgress = true
        listener.onCaptureStarted(shutterNanos)

        val captureStartTime = System.currentTimeMillis()
        
        val captureAction = {
            try {
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    cameraManager.rawImageReader?.surface?.let { addTarget(it) }
                    previewSurface?.let { addTarget(it) }
                    setTag(captureStartTime)
                    
                    PhotoParamMapper.applyPhotoParamsToRequest(
                        builder = this,
                        state = listener.getPhotoParamState(),
                        capabilities = listener.getCameraCapabilities(),
                        fallbackAutoIso = listener.getPreviewAutoValues().iso,
                        fallbackAutoShutterNanos = listener.getPreviewAutoValues().shutterNanos,
                        isStream = false
                    )
                    
                    listener.getFlashMode().applyToRequest(this, isStillCapture = true)
                    // Digital zoom
                    val zoomRatio = listener.getZoomRatio()
                    val chars = listener.getActiveCameraCharacteristics()
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
                    } else {
                        chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { rect ->
                            val cropW = (rect.width() / zoomRatio).toInt()
                            val cropH = (rect.height() / zoomRatio).toInt()
                            val left = (rect.width() - cropW) / 2
                            val top = (rect.height() - cropH) / 2
                            set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropW, top + cropH))
                        }
                    }

                    set(
                        CaptureRequest.JPEG_ORIENTATION,
                        CaptureOrientationCalculator.captureRotation(
                            sensorOrientation = listener.getSensorOrientation(),
                            lensFacing = listener.getLensFacing(),
                            deviceRotationDegrees = listener.getDeviceRotationDegrees(),
                        ).degrees
                    )
                }

                session.stopRepeating()
                session.capture(builder.build(), stillCaptureCallback, backgroundHandler)
            } catch (e: Exception) {
                Log.e("CaptureManager", "Capture failed", e)
                finishWithError("拍照失败")
            }
        }

        if (listener.getFlashMode() == FlashMode.SINGLE) {
            // Flash pulse logic would need to be handled by caller or passed in
            captureAction() 
        } else {
            captureAction()
        }
    }

    private fun calculateShutterNanos(): Long {
        val state = listener.getPhotoParamState()
        return if (state.shutter.isAuto) {
            listener.getPreviewAutoValues().shutterNanos ?: 100_000_000L
        } else {
            state.shutter.manualValue ?: 100_000_000L
        }
    }

    private val stillCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
            val wallTime = request.tag as? Long ?: System.currentTimeMillis()
            
            pendingCaptureResults[sensorTimestamp] = result
            captureStartTimeMap[sensorTimestamp] = wallTime
            
            listener.onReadyToResumePreview()
            processPendingCaptures()
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            finishWithError("拍照失败")
        }
    }

    fun onRawImageAvailable(image: Image) {
        pendingRawImages[image.timestamp] = image
        processPendingCaptures()
    }

    private fun processPendingCaptures() {
        val chars = listener.getActiveCameraCharacteristics() ?: return
        
        for (timestamp in pendingRawImages.keys()) {
            val image = pendingRawImages[timestamp]
            val result = pendingCaptureResults[timestamp]
            val wallTime = captureStartTimeMap[timestamp]
            
            if (image != null && result != null && wallTime != null) {
                pendingRawImages.remove(timestamp)
                pendingCaptureResults.remove(timestamp)
                captureStartTimeMap.remove(timestamp)
                
                dispatchCaptureTask(image, result, chars, wallTime)
            }
        }
    }

    private fun dispatchCaptureTask(image: Image, result: TotalCaptureResult, chars: CameraCharacteristics, wallTime: Long) {
        val format = preferenceManager.getString(PreferenceManager.KEY_OUTPUT_FORMAT, "JPG")
        isCaptureInProgress = false
        
        val task = prepareCaptureTask(image, result, chars, format, wallTime)
        
        captureExecutor.submit {
            try {
                if (format == "RAW" || format == "JPG + RAW") {
                    val uri = captureProcessor.saveRawImage(image, chars, result, task.exifOrientation, task.location, wallTime)
                    if (format == "RAW") listener.onCaptureCompleted(uri)
                }

                if (format == "JPG" || format == "JPG + RAW") {
                    executeCaptureTask(task, chars, result)
                }
            } finally {
                image.close()
            }
        }
    }

    private fun prepareCaptureTask(image: Image, result: TotalCaptureResult, chars: CameraCharacteristics, format: String, timestamp: Long): CaptureTask {
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        val lscStrength = 1.30f // Can be passed in
        val currentLsc = lscStrength * (if (iso <= 100) 1.0f else if (iso >= 3200) 0.5f else 1.0f - ((iso - 100).toFloat() / 3100f * 0.5f))
        
        val algoKey = mapAlgoKey(preferenceManager.getString(PreferenceManager.KEY_DEMOSAIC_ALGO, "Fast"))
        
        val rotation = CaptureOrientationCalculator.captureRotation(
            sensorOrientation = listener.getSensorOrientation(),
            lensFacing = listener.getLensFacing(),
            deviceRotationDegrees = listener.getDeviceRotationDegrees(),
        )

        val bayerPattern = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val blackLevelPattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val blackLevel = if (blackLevelPattern != null) {
            (blackLevelPattern.getOffsetForIndex(0, 0) + blackLevelPattern.getOffsetForIndex(1, 0) + 
             blackLevelPattern.getOffsetForIndex(0, 1) + blackLevelPattern.getOffsetForIndex(1, 1)) / 4
        } else 64
        val whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        
        val wbGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val wbArray = if (wbGains != null) floatArrayOf(wbGains.red, wbGains.greenEven, wbGains.greenOdd, wbGains.blue)
                      else floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f)

        val shadingMapObj = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        val shadingMapData = if (shadingMapObj != null) {
            val data = FloatArray(shadingMapObj.rowCount * shadingMapObj.columnCount * 4)
            shadingMapObj.copyGainFactors(data, 0)
            data
        } else null
        val shadingMapSize = if (shadingMapObj != null) android.util.Size(shadingMapObj.columnCount, shadingMapObj.rowCount) else null

        val cct = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) ?: chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
        val ccm = FloatArray(9)
        if (cct != null) {
            val rationals = arrayOfNulls<android.util.Rational>(9)
            cct.copyElements(rationals, 0)
            for (i in 0 until 9) ccm[i] = rationals[i]?.toFloat() ?: 0f
        } else {
            ccm[0] = 1f; ccm[4] = 1f; ccm[8] = 1f
        }

        val plane = image.planes[0]
        val cloneBuffer = ByteBuffer.allocateDirect(plane.buffer.remaining())
        cloneBuffer.put(plane.buffer)
        cloneBuffer.position(0)

        return CaptureTask(
            rawBuffer = cloneBuffer,
            width = image.width, height = image.height,
            rowStride = plane.rowStride, pixelStride = plane.pixelStride,
            captureStartTime = timestamp,
            metadata = CaptureMetadata(iso, wbArray, ccm, shadingMapData, shadingMapSize, bayerPattern, blackLevel, whiteLevel, currentLsc),
            algoKey = algoKey, savedFormat = format,
            rotationDegrees = rotation.degrees, exifOrientation = rotation.exifOrientation,
            cropRect = listener.getCurrentCropRect(),
            location = listener.getLocation()
        )
    }

    private fun executeCaptureTask(task: CaptureTask, chars: CameraCharacteristics, result: TotalCaptureResult) {
        try {
            // Produce a non-rotated bitmap from the engine, then rotate in Java for better compatibility
            val bitmap = DemosaicEngine.processFromBuffer(
                task.rawBuffer, task.width, task.height, task.rowStride, task.pixelStride,
                task.algoKey, task.metadata.wbArray, task.metadata.ccm, 
                task.metadata.shadingMap, task.metadata.shadingMapSize,
                task.metadata.bayerPattern, task.metadata.blackLevel, task.metadata.whiteLevel, task.metadata.lscStrength,
                task.cropRect, 0 // No rotation in engine
            )

            val uri = if (task.algoKey == "Mono" || task.algoKey == "None") {
                captureProcessor.savePngBitmap(bitmap, "", task.rotationDegrees, task.captureStartTime)
            } else {
                captureProcessor.saveJpegBitmap(bitmap, task.rotationDegrees, task.captureStartTime, listener.getJpgQuality(), chars, result, task.location)
            }
            
            listener.onCaptureCompleted(uri)
            bitmap.recycle()
        } catch (e: Exception) {
            Log.e("CaptureManager", "Execute task failed", e)
        }
    }

    private fun mapAlgoKey(savedAlgo: String): String {
        return when {
            savedAlgo.startsWith("RGB通道") -> "RgbCh"
            savedAlgo.startsWith("灰度") -> "Mono"
            savedAlgo.startsWith("无") -> "None"
            else -> savedAlgo.split('：', ':')[0].trim()
        }
    }

    private fun finishWithError(msg: String) {
        isCaptureInProgress = false
        listener.onCaptureFailed(msg)
    }

    fun release() {
        pendingRawImages.values.forEach { it.close() }
        pendingRawImages.clear()
        pendingCaptureResults.clear()
        captureStartTimeMap.clear()
    }
}
