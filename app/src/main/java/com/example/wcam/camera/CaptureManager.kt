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
import com.example.wcam.util.FileLogger
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
        fun onHardwareExposureStarted(exposureMs: Long)
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
        fun getImageQuality(): Int
        fun getCameraCapabilities(): CameraCapabilities
        fun getLocation(): android.location.Location?
    }

    private var config: com.example.wcam.util.ConfigManager? = null

    fun setConfig(configManager: com.example.wcam.util.ConfigManager) {
        config = configManager
    }

    private val pendingRawImages = ConcurrentHashMap<Long, Image>()
    private val pendingCaptureResults = ConcurrentHashMap<Long, TotalCaptureResult>()
    private val captureStartTimeMap = ConcurrentHashMap<Long, Long>()

    @Volatile
    var isCaptureInProgress = false
        private set

    private val MAX_PENDING_TASKS = 3 // Limit to prevent OOM
    private val pendingTaskCount = java.util.concurrent.atomic.AtomicInteger(0)

    fun capturePhoto(camera: CameraDevice, session: CameraCaptureSession, previewSurface: Surface?) {
        if (isCaptureInProgress) return
        if (pendingTaskCount.get() >= MAX_PENDING_TASKS) {
            Log.w("CaptureManager", "Too many pending tasks, dropping capture request")
            return
        }
        
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

                val startTime = System.currentTimeMillis()
                builder.setTag(startTime)
                Log.d("CaptureTimer", "[$startTime] Shutter pressed, submitting request...")

                session.stopRepeating()
                session.capture(builder.build(), stillCaptureCallback, backgroundHandler)
            } catch (e: Exception) {
                FileLogger.e("CaptureManager", "Capture failed", e)
                finishWithError("拍照失败")
            }
        }

        captureAction()
    }

    private fun calculateShutterNanos(): Long {
        val state = listener.getPhotoParamState()
        val defaultShutter = config?.getLong("default_shutter_nanos", 100_000_000L) ?: 100_000_000L
        return if (state.shutter.isAuto) {
            listener.getPreviewAutoValues().shutterNanos ?: defaultShutter
        } else {
            state.shutter.manualValue ?: defaultShutter
        }
    }

    private val stillCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            val exposureNanos = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME) ?: calculateShutterNanos()
            listener.onHardwareExposureStarted(exposureNanos / 1_000_000L)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val wallTime = request.tag as? Long ?: System.currentTimeMillis()
            Log.d("CaptureTimer", "[$wallTime] Hardware capture completed in ${System.currentTimeMillis() - wallTime}ms")
            
            val sensorTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
            pendingCaptureResults[sensorTimestamp] = result
            captureStartTimeMap[sensorTimestamp] = wallTime
            
            // 立即检查是否可以分发任务并恢复快门
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
        val formatString = preferenceManager.getString(PreferenceManager.KEY_OUTPUT_FORMAT, "JPG")
        val formats = formatString.split(",").filter { it.isNotEmpty() }.toSet()
        
        // 关键点：在分发任务给线程池的一瞬间，立即释放快门锁，让用户可以进行下一次拍摄
        isCaptureInProgress = false
        listener.onReadyToResumePreview()
        
        val task = prepareCaptureTask(image, result, chars, formatString, wallTime)
        
        pendingTaskCount.incrementAndGet()
        captureExecutor.submit {
            try {
                if (formats.contains("RAW")) {
                    val uri = captureProcessor.saveRawImage(image, chars, result, task.exifOrientation, task.location, wallTime)
                    listener.onCaptureCompleted(uri)
                }

                if (formats.contains("JPG") || formats.contains("HEIF")) {
                    executeCaptureTask(task, chars, result, formats)
                }
            } finally {
                image.close()
                pendingTaskCount.decrementAndGet()
            }
        }
    }

    private fun prepareCaptureTask(image: Image, result: TotalCaptureResult, chars: CameraCharacteristics, format: String, timestamp: Long): CaptureTask {
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        val lscStrength = config?.getFloat("lsc_strength", 1.30f) ?: 1.30f
        val currentLsc = lscStrength * (if (iso <= 100) 1.0f else if (iso >= 3200) 0.5f else 1.0f - ((iso - 100).toFloat() / 3100f * 0.5f))
        
        val algoKey = mapAlgoKey(preferenceManager.getString(PreferenceManager.KEY_DEMOSAIC_ALGO, "Fast"))
        
        val rotation = CaptureOrientationCalculator.captureRotation(
            sensorOrientation = listener.getSensorOrientation(),
            lensFacing = listener.getLensFacing(),
            deviceRotationDegrees = listener.getDeviceRotationDegrees(),
        )

        val bayerPattern = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val blackLevelPattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val defaultBlackLevel = config?.getInt("default_black_level", 64) ?: 64
        val blackLevel = if (blackLevelPattern != null) {
            (blackLevelPattern.getOffsetForIndex(0, 0) + blackLevelPattern.getOffsetForIndex(1, 0) + 
             blackLevelPattern.getOffsetForIndex(0, 1) + blackLevelPattern.getOffsetForIndex(1, 1)) / 4
        } else defaultBlackLevel
        val whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: (config?.getInt("default_white_level", 1023) ?: 1023)
        
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

    private fun executeCaptureTask(task: CaptureTask, chars: CameraCharacteristics, result: TotalCaptureResult, formats: Set<String>) {
        val wallTime = task.captureStartTime
        try {
            val demosaicStart = System.currentTimeMillis()
            val useLinear = preferenceManager.getString(PreferenceManager.KEY_ENCODING_MODE, "Gamma") == "Linear"

            // 8-bit Bitmap for JPEG
            var bitmap8: Bitmap? = null
            if (formats.contains("JPG")) {
                bitmap8 = DemosaicEngine.processFromBuffer(
                    task.rawBuffer, task.width, task.height, task.rowStride, task.pixelStride,
                    task.algoKey, task.metadata.wbArray, task.metadata.ccm, 
                    task.metadata.shadingMap, task.metadata.shadingMapSize,
                    task.metadata.bayerPattern, task.metadata.blackLevel, task.metadata.whiteLevel, task.metadata.lscStrength,
                    task.cropRect, task.rotationDegrees,
                    highBitDepth = false,
                    useLinear = useLinear
                )
            }

            // 16-bit Bitmap for HEIF (Always 16-bit regardless of linear/gamma)
            var bitmap16: Bitmap? = null
            if (formats.contains("HEIF")) {
                bitmap16 = DemosaicEngine.processFromBuffer(
                    task.rawBuffer, task.width, task.height, task.rowStride, task.pixelStride,
                    task.algoKey, task.metadata.wbArray, task.metadata.ccm, 
                    task.metadata.shadingMap, task.metadata.shadingMapSize,
                    task.metadata.bayerPattern, task.metadata.blackLevel, task.metadata.whiteLevel, task.metadata.lscStrength,
                    task.cropRect, task.rotationDegrees,
                    highBitDepth = true,
                    useLinear = useLinear
                )
            }
            Log.d("CaptureTimer", "[$wallTime] Demosaic finished in ${System.currentTimeMillis() - demosaicStart}ms")

            val isDemosaicMode = preferenceManager.getBoolean("settings_algo_is_demosaic", true)
            val savedAlgo = if (isDemosaicMode) preferenceManager.getString("algo_demosaic_choice", "Fast") 
                           else preferenceManager.getString("algo_simple_choice", "RGB通道")
            val algoDisplayName = savedAlgo.split('：', ':')[0].trim()

            val wmMode = preferenceManager.getString("wm_mode", "None")
            val wmConfig = WatermarkProcessor.WatermarkConfig(
                type = wmMode,
                showTime = preferenceManager.getBoolean("wm_show_time", true),
                showParams = preferenceManager.getBoolean("wm_show_params", true),
                showAlgo = preferenceManager.getBoolean("wm_show_algo", true),
                showText = preferenceManager.getBoolean("wm_show_text", false),
                customText = preferenceManager.getString("wm_custom_text", "wCam"),
                algoName = algoDisplayName
            )

            if (formats.contains("JPG") && bitmap8 != null) {
                val saveStart = System.currentTimeMillis()
                val uri = captureProcessor.saveJpegBitmap(bitmap8, 0, task.captureStartTime, listener.getImageQuality(), chars, result, task.location, wmConfig)
                Log.d("CaptureTimer", "[$wallTime] JPEG save finished in ${System.currentTimeMillis() - saveStart}ms")
                listener.onCaptureCompleted(uri)
                bitmap8.recycle()
            }
            
            if (formats.contains("HEIF") && bitmap16 != null) {
                val saveStart = System.currentTimeMillis()
                val uri = captureProcessor.saveHeifBitmap(bitmap16, 0, task.captureStartTime, listener.getImageQuality(), chars, result, task.location, wmConfig)
                Log.d("CaptureTimer", "[$wallTime] HEIF save finished in ${System.currentTimeMillis() - saveStart}ms")
                listener.onCaptureCompleted(uri)
                bitmap16.recycle()
            }
            
            Log.d("CaptureTimer", "[$wallTime] TOTAL process time: ${System.currentTimeMillis() - wallTime}ms")
        } catch (e: Exception) {
            FileLogger.e("CaptureManager", "Execute task failed", e)
        }
    }

    private fun mapAlgoKey(savedAlgo: String): String {
        return when {
            savedAlgo.startsWith("RGB通道") -> "RgbCh"
            savedAlgo.startsWith("灰度") -> "Mono"
            savedAlgo.startsWith("亮度") -> "LumaSum"
            savedAlgo.startsWith("原始拜尔") || savedAlgo.startsWith("无") -> "None"
            savedAlgo.startsWith("4合1") -> "Bin4"
            savedAlgo.startsWith("9合1") -> "Bin9"
            savedAlgo.startsWith("16合1") -> "Bin16"
            savedAlgo.startsWith("4倍亮度") -> "Bright4"
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
