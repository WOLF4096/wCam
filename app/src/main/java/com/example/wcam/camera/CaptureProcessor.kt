package com.example.wcam.camera

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ContentResolver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.content.Context

/**
 * Handles image processing, metadata writing, and saving to MediaStore.
 */
class CaptureProcessor(
    private val context: Context,
    private val rawMediaStore: RawMediaStore,
    private val config: com.example.wcam.util.ConfigManager,
    private val preferenceManager: com.example.wcam.PreferenceManager
) {
    private val contentResolver = context.contentResolver
    private var softwareName: String = "wCam"

    init {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val appLabel = context.applicationInfo.loadLabel(context.packageManager).toString()
            softwareName = "$appLabel ${pInfo.versionName}"
        } catch (e: Exception) {
            Log.e("CaptureProcessor", "Failed to get version info", e)
        }
    }

    fun saveRawImage(image: Image, chars: CameraCharacteristics, result: TotalCaptureResult, exifOrientation: Int, location: android.location.Location?, wallTime: Long): Uri {
        return rawMediaStore.saveRawImage(image, chars, result, exifOrientation, location, wallTime)
    }

    fun saveJpegBitmap(
        bitmap: Bitmap, 
        rotationDegrees: Int, 
        timestamp: Long, 
        quality: Int,
        characteristics: CameraCharacteristics?,
        captureResult: TotalCaptureResult?,
        location: android.location.Location? = null,
        wmConfig: WatermarkProcessor.WatermarkConfig? = null
    ): Uri {
        val folderName = config.getString("default_storage_folder", "wCam")
        
        // 1. Rotate first
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotatedBitmap = if (rotationDegrees != 0) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
        
        // 2. Apply Watermark to rotated bitmap
        val finalBitmap = if (wmConfig != null && wmConfig.type != "None") {
            WatermarkProcessor().applyWatermark(rotatedBitmap, wmConfig, characteristics, captureResult, 0)
        } else {
            rotatedBitmap
        }

        val fileName = createPhotoDisplayName(timestamp, "jpg")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + folderName)
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore entry")

        contentResolver.openOutputStream(uri)?.use { os ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, os)
            os.flush()
        } ?: throw IOException("Failed to open output stream")
        
        // Write EXIF
        try {
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                writeExifMetadata(exif, characteristics, captureResult)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                
                if (preferenceManager.getBoolean("save_location", true)) {
                    location?.let { exif.setGpsInfo(it) }
                }
                
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.e("CaptureProcessor", "Failed to write EXIF metadata", e)
        }
        
        if (finalBitmap != rotatedBitmap) finalBitmap.recycle()
        if (rotatedBitmap != bitmap) rotatedBitmap.recycle()
        return uri
    }

    fun savePngBitmap(
        bitmap: Bitmap, 
        suffix: String, 
        rotationDegrees: Int, 
        timestamp: Long,
        characteristics: CameraCharacteristics? = null,
        captureResult: TotalCaptureResult? = null,
        wmConfig: WatermarkProcessor.WatermarkConfig? = null
    ): Uri {
        val folderName = config.getString("default_storage_folder", "wCam")
        
        // 1. Rotate first
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotatedBitmap = if (rotationDegrees != 0) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        // 2. Apply Watermark to rotated bitmap
        val finalBitmap = if (wmConfig != null && wmConfig.type != "None") {
            WatermarkProcessor().applyWatermark(rotatedBitmap, wmConfig, characteristics, captureResult, 0)
        } else {
            rotatedBitmap
        }
        
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timestamp))
        val fileName = if (suffix.isEmpty()) "$ts.png" else "${ts}_$suffix.png"
        
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + folderName)
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore entry")

        contentResolver.openOutputStream(uri)?.use { os ->
            finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
        } ?: throw IOException("Failed to open output stream")
        
        if (finalBitmap != rotatedBitmap) finalBitmap.recycle()
        if (rotatedBitmap != bitmap) rotatedBitmap.recycle()
        return uri
    }

    private fun writeExifMetadata(exif: ExifInterface, characteristics: CameraCharacteristics?, result: TotalCaptureResult?) {
        if (result == null || characteristics == null) return

        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, softwareName)
        
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val aperture = result.get(CaptureResult.LENS_APERTURE) ?: characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
        val focalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val evIndex = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
        val evStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat()
        val flashState = result.get(CaptureResult.FLASH_STATE)

        iso?.let {
            val isoStr = it.toString()
            exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, isoStr)
            @Suppress("DEPRECATION")
            exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, isoStr)
        }
        
        exposureTime?.let {
            if (it > 0) {
                val exposureInSeconds = it / 1_000_000_000.0
                exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, String.format(Locale.US, "%.6f", exposureInSeconds))
            }
        }
        
        aperture?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString()) }
        focalLength?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, it.toString()) }
        
        if (evIndex != null && evStep != null) {
            val ev = evIndex * evStep
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, String.format(Locale.US, "%.1f", ev))
        }

        val flashFired = if (flashState != null) {
            flashState == CaptureResult.FLASH_STATE_FIRED || flashState == CaptureResult.FLASH_STATE_PARTIAL
        } else {
            false
        }
        exif.setAttribute(ExifInterface.TAG_FLASH, if (flashFired) "1" else "0")
        
        val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val now = Date()
        val dateStr = sdf.format(now)
        exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateStr)
    }

    private fun createPhotoDisplayName(timestampMillis: Long, extension: String): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        return "${formatter.format(Date(timestampMillis))}.$extension"
    }
}
