package com.example.wcam.camera

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.location.Location
import android.media.Image
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
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
    private var currentLogicalId = "0"
    private var currentPhysicalId: String? = null

    fun start() {
        startBackgroundThread()
    }

    fun stop() {
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
            )
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
        val characteristics = manager.getCameraCharacteristics(camera.id)
        activeCharacteristics = characteristics
        
        val handler = backgroundHandler ?: return
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
        val jpg = cameraManager.jpegImageReader ?: return
        val handler = backgroundHandler ?: return
        val characteristics = activeCharacteristics ?: return
        val texture = listener.getPreviewTexture().surfaceTexture ?: return

        try {
            captureSession?.close()
            
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSize = if (viewModel.isPhotoMode.value) {
                map?.getOutputSizes(SurfaceTexture::class.java)
                    ?.filter { Math.abs(it.width.toFloat() / it.height - 1.333f) < 0.1 }
                    ?.sortedByDescending { it.width * it.height }
                    ?.find { it.width <= 2500 } ?: Size(1440, 1080)
            } else {
                Size(1920, 1080)
            }

            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            listener.onPreviewReady(previewSize)

            previewSurface = Surface(texture)
            val surf = previewSurface ?: return

            previewRequestBuilder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surf)
            }

            sessionManager.createSession(
                cam,
                listOf(surf, raw.surface, jpg.surface),
                currentPhysicalId,
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
