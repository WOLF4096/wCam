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

    fun logCameraArchitecture(context: Context) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        Log.i(TAG, "==== CAMERA ARCHITECTURE DEBUG START ====")
        try {
            val logicalIds = manager.cameraIdList
            for (id in logicalIds) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    else -> "EXTERNAL"
                }
                
                Log.i(TAG, "LOGICAL ID: $id ($facing)")
                
                // Check if Multi-Camera
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val physicalIds = chars.physicalCameraIds
                    if (physicalIds.isNotEmpty()) {
                        Log.i(TAG, "  -> This is a LOGICAL camera consisting of physical IDs: ${physicalIds.joinToString(", ")}")
                        for (pId in physicalIds) {
                            logIdCapabilities(manager, pId, "PHYSICAL (Sub)")
                        }
                    } else {
                        Log.i(TAG, "  -> This is a standalone PHYSICAL camera.")
                    }
                }
                
                logIdCapabilities(manager, id, "LOGICAL (Main)")
                Log.i(TAG, "--------------------------------------------")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Architecture debug failed: ${e.message}")
        }
        Log.i(TAG, "==== CAMERA ARCHITECTURE DEBUG END ====")
    }

    private fun logIdCapabilities(manager: CameraManager, id: String, label: String) {
        try {
            val chars = manager.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            val cfaStr = when(cfa) {
                CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
                CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
                CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
                CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
                else -> "UNKNOWN ($cfa)"
            }

            Log.i(TAG, "  [$label ID: $id] CFA: $cfaStr")
            
            Log.i(TAG, "    [RAW_SENSOR Sizes]")
            map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.forEach { size ->
                Log.i(TAG, "      - ${size.width}x${size.height}")
            }
            
            Log.i(TAG, "    [SurfaceTexture (Preview) Sizes]")
            map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.take(10)?.forEach { size ->
                Log.i(TAG, "      - ${size.width}x${size.height}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "    Failed to get capabilities for ID $id: ${e.message}")
        }
    }

    fun logAllCameraStreams(context: Context, outVideoCapabilities: MutableMap<String, List<VideoSizeInfo>>) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        
        Log.i(TAG, "--- Starting comprehensive video capability detection ---")
        val totalStart = System.currentTimeMillis()
        
        val allPossibleIds = mutableSetOf<String>()
        allPossibleIds.addAll(manager.cameraIdList)
        for (i in 0..30) allPossibleIds.add(i.toString())

        for (id in allPossibleIds.sortedBy { it.toIntOrNull() ?: 999 }) {
            try {
                val start = System.currentTimeMillis()
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
                        val hsRanges = map.getHighSpeedVideoFpsRangesFor(hsSize)
                        val maxHsFps = hsRanges.maxOfOrNull { it.upper } ?: 0
                        capabilities.add(VideoSizeInfo(hsSize.width, hsSize.height, maxFps = maxHsFps, isHighSpeed = true))
                    }
                }

                outVideoCapabilities[id] = capabilities
                Log.i(TAG, "Camera ID: $id - Detected ${capabilities.size} configs in ${System.currentTimeMillis() - start}ms")
            } catch (e: Exception) {
                // Not an accessible ID
            }
        }
        Log.i(TAG, "logAllCameraStreams total took ${System.currentTimeMillis() - totalStart}ms")
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
        Log.i(TAG, "--- Exhaustive Camera Parameter Dump ---")

        try {
            // Some vendors hide physical cameras from cameraIdList. 
            // We'll probe a range of IDs and also check physical IDs of logical cameras.
            val discoveredIds = mutableSetOf<String>()
            discoveredIds.addAll(manager.cameraIdList)
            for (i in 0..30) {
                val idStr = i.toString()
                try {
                    manager.getCameraCharacteristics(idStr)
                    discoveredIds.add(idStr)
                } catch (e: Exception) { /* skip */ }
            }

            for (id in discoveredIds.sortedBy { it.toIntOrNull() ?: 999 }) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                    else -> "UNKNOWN"
                }

                Log.i(TAG, "CAMERA ID: $id ($facing)")

                // Logical vs Physical info
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val physicalIds = chars.physicalCameraIds
                    if (physicalIds.isNotEmpty()) {
                        Log.i(TAG, "  Type: LOGICAL (Subs: ${physicalIds.joinToString(", ")})")
                    } else {
                        Log.i(TAG, "  Type: PHYSICAL")
                    }
                }

                // Optics & Sensor
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                Log.i(TAG, "  Focal Lengths: ${focalLengths?.joinToString(", ") ?: "N/A"} mm")
                Log.i(TAG, "  Apertures: f/${apertures?.joinToString(", f/") ?: "N/A"}")
                Log.i(TAG, "  Sensor Res: ${pixelArray?.width}x${pixelArray?.height}")

                // Capabilities
                val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val supportsRaw = capabilities.any { it == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW }
                val supportsManual = capabilities.any { it == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR }
                Log.i(TAG, "  RAW: $supportsRaw, Manual: $supportsManual")

                // FPS & Streams
                val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                Log.i(TAG, "  AE FPS Ranges: ${fpsRanges?.joinToString { "[${it.lower}-${it.upper}]" }}")

                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (map != null) {
                    Log.i(TAG, "  Supported Stream Sizes (SurfaceTexture):")
                    map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.sortedByDescending { it.width * it.height }?.take(15)?.forEach { size ->
                        val minDuration = map.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, size)
                        val maxFps = if (minDuration > 0) (1_000_000_000L / minDuration).toInt() else 0
                        Log.i(TAG, "    - ${size.width}x${size.height} (Max FPS: $maxFps)")
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        map.highSpeedVideoSizes?.forEach { hsSize ->
                            val hsRanges = map.getHighSpeedVideoFpsRangesFor(hsSize)
                            Log.i(TAG, "  [High-Speed/Slow-Mo] ${hsSize.width}x${hsSize.height} FPS: ${hsRanges.joinToString { "[${it.lower}-${it.upper}]" }}")
                        }
                    }
                }
                Log.i(TAG, "--------------------------------------------------------")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dump failed: ${e.message}")
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
