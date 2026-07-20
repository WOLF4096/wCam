package com.example.wcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager as AndroidCameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import com.example.wcam.util.FileLogger

/**
 * Manages the CameraDevice lifecycle and ImageReaders.
 */
class CameraManager(
    private val context: Context,
    private val listener: CameraListener
) {

    interface CameraListener {
        fun onCameraOpened(camera: CameraDevice)
        fun onCameraDisconnected()
        fun onCameraError(error: Int)
        fun onRawImageAvailable(image: Image)
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as AndroidCameraManager
    private var cameraDevice: CameraDevice? = null
    
    var rawImageReader: ImageReader? = null
        private set

    @SuppressLint("MissingPermission")
    fun openCamera(logicalId: String, backgroundHandler: Handler) {
        try {
            Log.d("PerfLog", "manager.openCamera start for $logicalId")
            manager.openCamera(logicalId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            FileLogger.e("CameraManager", "Failed to open camera", e)
        }
    }

    fun closeCamera() {
        cameraDevice?.close()
        cameraDevice = null
        releaseReaders()
    }

    fun releaseReaders() {
        rawImageReader?.close()
        rawImageReader = null
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d("PerfLog", "CameraDevice.StateCallback.onOpened")
            cameraDevice = camera
            listener.onCameraOpened(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
            listener.onCameraDisconnected()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
            listener.onCameraError(error)
        }
    }

    fun configureImageReaders(characteristics: CameraCharacteristics, backgroundHandler: Handler): Boolean {
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return false
        
        val largestRawSize = streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
        
        val rawWidth = largestRawSize?.width ?: 4080
        val rawHeight = largestRawSize?.height ?: 3072

        if (rawImageReader == null || rawImageReader!!.width != rawWidth || rawImageReader!!.height != rawHeight) {
            rawImageReader?.close()
            // REDUCED BUFFER COUNT: Xiaomi mid-range devices (like Note 12 Turbo) 
            // often fail session config if RAW buffers are > 2 or 3.
            rawImageReader = ImageReader.newInstance(rawWidth, rawHeight, ImageFormat.RAW_SENSOR, 2)
        }
        
        rawImageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            listener.onRawImageAvailable(image)
        }, backgroundHandler)

        return true
    }
}
