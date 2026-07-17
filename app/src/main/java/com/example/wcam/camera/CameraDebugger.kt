package com.example.wcam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import java.util.Locale

/**
 * Helper class for camera diagnostics and debugging vendor-specific features.
 */
object CameraDebugger {
    private const val TAG = "CameraDebugger"

    fun logAllCameraStreams(context: Context, outVideoCapabilities: MutableMap<String, List<VideoSizeInfo>>) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val physicalIds = listOf("2", "4", "3", "1")
        
        Log.i(TAG, "--- Starting video capability detection ---")
        
        for (id in physicalIds) {
            try {
                val chars = manager.getCameraCharacteristics(id)
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

                val capabilities = mutableListOf<VideoSizeInfo>()

                // Regular video sizes
                map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.forEach { size ->
                    val minDuration = map.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, size)
                    val maxFps = if (minDuration > 0) (1_000_000_000L / minDuration).toInt() else 0
                    capabilities.add(VideoSizeInfo(size.width, size.height, maxFps))
                }

                // High speed video sizes
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    map.highSpeedVideoSizes?.forEach { hsSize ->
                        val hsFpsRanges = map.getHighSpeedVideoFpsRangesFor(hsSize)
                        val maxHsFps = hsFpsRanges.maxOfOrNull { it.upper } ?: 0
                        capabilities.add(VideoSizeInfo(hsSize.width, hsSize.height, maxHsFps, isHighSpeed = true))
                    }
                }
                
                outVideoCapabilities[id] = capabilities
                Log.i(TAG, "Camera ID: $id - Found ${capabilities.size} configurations")
            } catch (e: Exception) {
                Log.e(TAG, "Could not read info for camera $id: ${e.message}")
            }
        }
    }

    fun verifyHighResRawSupport(context: Context) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        Log.i(TAG, "--- Verifying 200MP RAW support ---")
        try {
            val allIds = manager.cameraIdList.toMutableList()
            listOf("2", "3", "4", "5").forEach { if (!allIds.contains(it)) allIds.add(it) }

            for (id in allIds) {
                val chars = try { manager.getCameraCharacteristics(id) } catch (e: Exception) { continue }
                
                val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val isUltraHighResSupported = capabilities.any { it == 16 }
                
                Log.i(TAG, "Camera ID: $id, UltraHighRes(16): $isUltraHighResSupported")
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val maxResMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                    if (maxResMap != null) {
                        maxResMap.getOutputSizes(ImageFormat.RAW_SENSOR)?.forEach { size ->
                            val mp = (size.width.toLong() * size.height) / 1_000_000.0
                            Log.i(TAG, "  [MAX_RES] RAW: ${size.width}x${size.height} (~${String.format(Locale.US, "%.1f", mp)} MP)")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Verification failed: ${e.message}")
        }
    }

    fun dumpVendorKeys(context: Context) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        Log.i(TAG, "--- Dumping Vivo/MTK vendor keys ---")
        try {
            val allIds = manager.cameraIdList.toMutableList()
            listOf("2", "3", "4", "5").forEach { if (!allIds.contains(it)) allIds.add(it) }

            for (id in allIds) {
                val chars = try { manager.getCameraCharacteristics(id) } catch (e: Exception) { continue }
                Log.i(TAG, "Camera ID: $id")
                
                chars.keys.forEach { key ->
                    if (key.name.contains("remosaic", true) || key.name.contains("highres", true)) {
                        Log.i(TAG, "  [Char Key] ${key.name} = ${formatValue(chars.get(key))}")
                    }
                }

                chars.availableCaptureRequestKeys.forEach { key ->
                    if (key.name.contains("remosaic", true) || key.name.contains("highres", true) || key.name.contains("ultra", true)) {
                        Log.i(TAG, "  [Request Key Found] ${key.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dump failed: ${e.message}")
        }
    }

    fun logDetailedCameraInfo(context: Context) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        Log.i(TAG, "========================================================")
        Log.i(TAG, "DEVICE INFO: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        Log.i(TAG, "--- Detailed Camera Parameter Dump ---")

        try {
            val allIds = manager.cameraIdList
            for (id in allIds) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN"
                }

                Log.i(TAG, "CAMERA ID: $id ($facing)")

                // Physical IDs if this is a logical camera
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val physicalIds = chars.physicalCameraIds
                    if (physicalIds.isNotEmpty()) {
                        Log.i(TAG, "  Physical Camera IDs: ${physicalIds.joinToString(", ")}")
                    }
                }

                // Focal Lengths
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                Log.i(TAG, "  Focal Lengths: ${focalLengths?.joinToString(", ") ?: "N/A"} mm")

                // Resolutions
                val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                Log.i(TAG, "  Sensor Pixel Array: ${pixelArray?.width}x${pixelArray?.height}")
                Log.i(TAG, "  Sensor Active Array: ${activeArray?.width()}x${activeArray?.height()}")

                // RAW Support
                val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val supportsRaw = capabilities.any { it == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW }
                Log.i(TAG, "  RAW Support: $supportsRaw")

                // Video/Stream Sizes
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (map != null) {
                    val streamSizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                    if (streamSizes != null) {
                        Log.i(TAG, "  Supported Stream Sizes (SurfaceTexture):")
                        streamSizes.sortedByDescending { it.width * it.height }.forEach { size ->
                            val minDuration = map.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, size)
                            val maxFps = if (minDuration > 0) (1_000_000_000L / minDuration).toInt() else 0
                            Log.i(TAG, "    - ${size.width}x${size.height} (Max FPS: $maxFps)")
                        }
                    }
                }
                Log.i(TAG, "--------------------------------------------------------")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump camera info: ${e.message}")
        }
        Log.i(TAG, "========================================================")
    }

    private fun formatValue(value: Any?): String {
        return when (value) {
            is IntArray -> value.joinToString(", ")
            is FloatArray -> value.joinToString(", ")
            is LongArray -> value.joinToString(", ")
            is Array<*> -> value.joinToString(", ")
            is android.util.Size -> "${value.width}x${value.height}"
            is android.graphics.Rect -> "${value.left},${value.top},${value.right},${value.bottom}"
            else -> value.toString()
        }
    }
}
