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

/**
 * Handles image processing, metadata writing, and saving to MediaStore.
 */
class CaptureProcessor(
    private val contentResolver: ContentResolver,
    private val rawMediaStore: RawMediaStore
) {

    fun saveRawImage(image: Image, chars: CameraCharacteristics, result: TotalCaptureResult, wallTime: Long): Uri {
        return rawMediaStore.saveRawImage(image, chars, result, 0, wallTime)
    }

    fun saveJpegBitmap(
        bitmap: Bitmap, 
        rotationDegrees: Int, 
        timestamp: Long, 
        quality: Int,
        characteristics: CameraCharacteristics?,
        captureResult: TotalCaptureResult?
    ): Uri {
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotatedBitmap = if (rotationDegrees != 0) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
        
        val fileName = createPhotoDisplayName(timestamp, "jpg")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/wCam")
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore entry")

        contentResolver.openOutputStream(uri)?.use { os ->
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, os)
        } ?: throw IOException("Failed to open output stream")
        
        // Write EXIF
        try {
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                writeExifMetadata(exif, characteristics, captureResult)
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.e("CaptureProcessor", "Failed to write EXIF", e)
        }
        
        if (rotatedBitmap != bitmap) rotatedBitmap.recycle()
        return uri
    }

    fun savePngBitmap(bitmap: Bitmap, suffix: String, rotationDegrees: Int, timestamp: Long): Uri {
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotatedBitmap = if (rotationDegrees != 0) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
        
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timestamp))
        val fileName = if (suffix.isEmpty()) "$ts.png" else "${ts}_$suffix.png"
        
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/wCam")
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore entry")

        contentResolver.openOutputStream(uri)?.use { os ->
            rotatedBitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
        } ?: throw IOException("Failed to open output stream")
        
        if (rotatedBitmap != bitmap) rotatedBitmap.recycle()
        return uri
    }

    private fun writeExifMetadata(exif: ExifInterface, characteristics: CameraCharacteristics?, result: TotalCaptureResult?) {
        if (result == null || characteristics == null) return

        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "wCam 1.1")
        
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val aperture = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
        val focalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH)

        iso?.let { exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString()) }
        exposureTime?.let {
            val exposureInSeconds = it / 1_000_000_000.0
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exposureInSeconds.toString())
        }
        aperture?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString()) }
        focalLength?.let { exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, it.toString()) }
        
        // Date Time
        val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val now = Date()
        exif.setAttribute(ExifInterface.TAG_DATETIME, sdf.format(now))
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, sdf.format(now))
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, sdf.format(now))
    }

    private fun createPhotoDisplayName(timestampMillis: Long, extension: String): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        return "${formatter.format(Date(timestampMillis))}.$extension"
    }
}
