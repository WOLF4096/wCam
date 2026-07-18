package com.example.wcam.camera

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.location.Location
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.TextView
import com.example.wcam.PreferenceManager
import com.example.wcam.ui.ManualParamController
import com.example.wcam.viewmodel.CameraViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Coordinates the interactions between different camera components.
 * This class takes over the complex logic from MainActivity.
 */
class CameraCoordinator(
    private val context: Context,
    private val viewModel: CameraViewModel,
    private val preferenceManager: PreferenceManager,
    private val captureProcessor: CaptureProcessor,
    private val config: com.example.wcam.util.ConfigManager,
    private val listener: CoordinatorListener
) : CameraManager.CameraListener, CaptureSessionManager.SessionListener, CaptureManager.CaptureListener {

    interface CoordinatorListener {
        fun onCameraOpened(characteristics: CameraCharacteristics)
        fun onSessionConfigured()
        fun onPreviewReady(size: Size)
        fun onCaptureStarted(shutterNanos: Long)
        fun onCaptureCompleted(uri: Uri?)
        fun onCaptureFailed(message: String)
        fun onReadyToResumePreview()
        fun onError(message: String)
        fun getPreviewTexture(): TextureView
        fun getCountdownTextView(): TextView?
        
        // State getters that remain in Activity for now (UI related or calculated)
        fun getDeviceRotationDegrees(): Int
        fun getSensorOrientation(): Int
        fun getLensFacing(): Int?
        fun getCurrentCropRect(): Rect?
        fun getJpgQuality(): Int
        fun getCameraCapabilities(): CameraCapabilities
        fun getLocation(): Location?
        fun getPreviewAutoValues(): ManualParamController.PreviewAutoValues
    }

    private var cameraManager: CameraManager = CameraManager(context, this)
    private var sessionManager: CaptureSessionManager = CaptureSessionManager(this)
    private var captureManager: CaptureManager? = null
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSurface: Surface? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var captureExecutor: ExecutorService? = null

    private var activeCharacteristics: CameraCharacteristics? = null
    private var currentLogicalId = ""
    private var currentPhysicalId: String? = null

    private var countdownManager: CountdownManager? = null
    private var autoCaptureManager: AutoCaptureManager? = null
    private val watermarkProcessor = WatermarkProcessor()

    fun start() {
        startBackgroundThread()
    }

    fun stop() {
        autoCaptureManager?.stop()
        autoCaptureManager = null
        closeCamera()
        stopBackgroundThread()
    }

    fun openCamera(logicalId: String, physicalId: String? = null) {
        currentLogicalId = logicalId
        currentPhysicalId = physicalId
        
        val handler = backgroundHandler ?: return
        cameraManager.openCamera(logicalId, handler)
    }

    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraManager.closeCamera()
        cameraDevice = null
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraCoordinatorBackground").also { it.start() }
            val handler = Handler(backgroundThread!!.looper)
            backgroundHandler = handler
            
            val executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
            captureExecutor = executor
            
            captureManager = CaptureManager(
                cameraManager,
                captureProcessor,
                preferenceManager,
                executor,
                handler,
                this
            ).apply { setConfig(config) }

            autoCaptureManager = AutoCaptureManager(handler) {
                capturePhoto()
            }
        }
    }

    private fun stopBackgroundThread() {
        captureManager?.release()
        captureManager = null
        
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
            captureExecutor?.shutdown()
            captureExecutor = null
        } catch (e: InterruptedException) {
            Log.e("CameraCoordinator", "Interrupted while stopping background thread", e)
        }
    }

    // CameraManager.CameraListener implementation
    override fun onCameraOpened(camera: CameraDevice) {
        cameraDevice = camera
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        
        // Use currentPhysicalId if available for characteristics and readers
        val charId = currentPhysicalId ?: currentLogicalId
        val characteristics = manager.getCameraCharacteristics(charId)
        activeCharacteristics = characteristics
        
        val handler = backgroundHandler ?: return
        // Re-configure readers to match the current lens's capabilities
        cameraManager.configureImageReaders(characteristics, handler)
        
        listener.onCameraOpened(characteristics)
        createPreviewSession()
    }

    override fun onCameraDisconnected() {
        cameraDevice = null
    }

    override fun onCameraError(error: Int) {
        cameraDevice = null
        listener.onError("Camera error: $error")
    }

    override fun onRawImageAvailable(image: Image) {
        captureManager?.onRawImageAvailable(image)
    }

    // CaptureSessionManager.SessionListener implementation
    override fun onSessionConfigured(session: CameraCaptureSession) {
        captureSession = session
        listener.onSessionConfigured()
    }

    override fun onSessionConfigureFailed() {
        listener.onError("Session configuration failed")
    }

    fun createPreviewSession() {
        val cam = cameraDevice ?: return
        val raw = cameraManager.rawImageReader ?: return
        val handler = backgroundHandler ?: return
        val characteristics = activeCharacteristics ?: return
        val texture = listener.getPreviewTexture().surfaceTexture ?: return

        try {
            captureSession?.close()
            
            // Try to get size and FPS from JSON config
            val targetId = currentPhysicalId ?: currentLogicalId
            val camConfig = config.getCameraConfig(targetId)
            var previewSize: Size? = null
            var targetFps: Int? = null

            camConfig?.previewSize?.let { sizeStr ->
                try {
                    val parts = sizeStr.split("@")
                    val res = parts[0].split("x")
                    previewSize = Size(res[0].toInt(), res[1].toInt())
                    if (parts.size > 1) targetFps = parts[1].toInt()
                } catch (e: Exception) {
                    Log.e("CameraCoordinator", "Invalid preview_size format in JSON: $sizeStr")
                }
            }

            // Fallback to automatic finding if not in config
            if (previewSize == null) {
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val maxWidth = config.getInt("preview_max_width", 2500)
                val preferredRatio = config.getFloat("preferred_aspect_ratio", 1.333f)

                previewSize = if (viewModel.isPhotoMode.value) {
                    map?.getOutputSizes(SurfaceTexture::class.java)
                        ?.filter { Math.abs(it.width.toFloat() / it.height - preferredRatio) < 0.1 }
                        ?.sortedByDescending { it.width * it.height }
                        ?.find { it.width <= maxWidth } ?: Size(1440, 1080)
                } else {
                    Size(1920, 1080)
                }
            }

            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            listener.onPreviewReady(previewSize)

            previewSurface = Surface(texture)
            val surf = previewSurface ?: return

            previewRequestBuilder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surf)
                
                // Apply target FPS if specified in JSON
                targetFps?.let { fps ->
                    val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    val bestRange = ranges?.find { it.upper == fps && it.lower == fps }
                        ?: ranges?.find { it.upper == fps }
                    bestRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                }
            }

            // Determine if we really need to pass a physical ID.
            // Only pass it if it's a sub-lens of a logical multi-camera (i.e., different from logical ID).
            val effectivePhysicalId = if (currentPhysicalId != null && currentPhysicalId != currentLogicalId) {
                currentPhysicalId
            } else {
                null
            }

            sessionManager.createSession(
                cam,
                listOf(surf, raw.surface),
                effectivePhysicalId,
                previewRequestBuilder!!.build(),
                handler,
                raw.surface
            )
        } catch (e: Exception) {
            Log.e("CameraCoordinator", "Failed to create preview session", e)
            listener.onError("Preview error: ${e.message}")
        }
    }

    fun resumePreview(callback: CameraCaptureSession.CaptureCallback) {
        val session = captureSession ?: return
        val request = previewRequestBuilder ?: return
        val handler = backgroundHandler ?: return
        
        try {
            session.setRepeatingRequest(request.build(), callback, handler)
        } catch (e: CameraAccessException) {
            Log.e("CameraCoordinator", "Failed to resume preview", e)
        }
    }

    fun capturePhoto() {
        // Handle Auto Capture first-time trigger
        val autoMode = preferenceManager.getString("auto_capture_mode", "None")
        if (autoCaptureManager != null && autoMode != "None") {
            if (autoMode == "Interval") {
                val interval = preferenceManager.getString("auto_interval_sec", "5").toIntOrNull() ?: 5
                val duration = preferenceManager.getString("auto_interval_duration", "60").toIntOrNull() ?: 60
                autoCaptureManager?.startInterval(interval, duration)
            } else if (autoMode == "Continuous") {
                val count = preferenceManager.getString("auto_continuous_count", "10").toIntOrNull() ?: 10
                autoCaptureManager?.startContinuous(count)
            }
            return
        }

        val seconds = preferenceManager.getInt("countdown_sec", 0)
        if (countdownManager == null) {
            // This is a bit hacky, normally listener should provide the view
            // but we can't easily change the interface right now.
            // I'll assume we can get it or pass it.
            // Let's add it to CoordinatorListener.
        }
        
        listener.getCountdownTextView()?.let { tv ->
            if (countdownManager == null) {
                countdownManager = CountdownManager(Handler(android.os.Looper.getMainLooper()), tv) {
                    executeCaptureAction()
                }
            }
            countdownManager?.start(seconds)
        } ?: run {
            executeCaptureAction()
        }
    }

    private fun executeCaptureAction() {
        val cam = cameraDevice ?: return
        val session = captureSession ?: return
        val surf = previewSurface ?: return
        
        captureManager?.capturePhoto(cam, session, surf)
    }

    fun triggerFocus(x: Float, y: Float, textureView: TextureView, callback: CameraCaptureSession.CaptureCallback) {
        val chars = activeCharacteristics ?: return
        val builder = previewRequestBuilder ?: return
        val handler = backgroundHandler ?: return
        
        sessionManager.triggerFocusAndMetering(x, y, textureView, chars, builder, handler, callback)
    }

    fun toggleAeAfLock(locked: Boolean) {
        val builder = previewRequestBuilder ?: return
        sessionManager.toggleAeAfLock(locked, builder)
    }

    // CaptureManager.CaptureListener implementation
    override fun onCaptureStarted(shutterNanos: Long) {
        listener.onCaptureStarted(shutterNanos)
    }

    override fun onCaptureCompleted(uri: Uri?) {
        listener.onCaptureCompleted(uri)
        autoCaptureManager?.onFrameProcessed()
    }

    override fun onCaptureFailed(message: String) {
        listener.onCaptureFailed(message)
    }

    override fun onReadyToResumePreview() {
        listener.onReadyToResumePreview()
    }

    override fun getActiveCameraCharacteristics(): CameraCharacteristics? = activeCharacteristics
    override fun getPhotoParamState(): PhotoParamState = viewModel.photoParamState.value
    override fun getPreviewAutoValues(): ManualParamController.PreviewAutoValues = listener.getPreviewAutoValues()
    override fun getFlashMode(): FlashMode = viewModel.currentFlashMode.value
    override fun getZoomRatio(): Float = viewModel.zoomRatio.value
    override fun getDeviceRotationDegrees(): Int = listener.getDeviceRotationDegrees()
    override fun getSensorOrientation(): Int = listener.getSensorOrientation()
    override fun getLensFacing(): Int? = listener.getLensFacing()
    override fun getCurrentCropRect(): Rect? = listener.getCurrentCropRect()
    override fun getJpgQuality(): Int = listener.getJpgQuality()
    override fun getCameraCapabilities(): CameraCapabilities = listener.getCameraCapabilities()
    override fun getLocation(): Location? = listener.getLocation()
    
    fun getActiveCharacteristics(): CameraCharacteristics? = activeCharacteristics
    fun getBackgroundHandler(): Handler? = backgroundHandler
    fun getPreviewRequestBuilder(): CaptureRequest.Builder? = previewRequestBuilder
    fun isCaptureInProgress(): Boolean = captureManager?.isCaptureInProgress ?: false
}
