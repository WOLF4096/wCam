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
import com.example.wcam.util.FileLogger
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ContentResolver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.content.Context
import android.media.MediaScannerConnection
import androidx.heifwriter.HeifWriter

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
            FileLogger.e("CaptureProcessor", "Failed to get version info", e)
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
        
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotatedBitmap = if (rotationDegrees != 0) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
        
        val finalBitmap = if (wmConfig != null && wmConfig.type != "None") {
            WatermarkProcessor().applyWatermark(rotatedBitmap, wmConfig, characteristics, captureResult, 0, config)
        } else {
            rotatedBitmap
        }

        val fileName = createPhotoDisplayName(timestamp, "jpg")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + folderName + "/")
            } else {
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), folderName)
                if (!folder.exists()) folder.mkdirs()
                val file = File(folder, fileName)
                put(MediaStore.Images.Media.DATA, file.absolutePath)
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore entry")

        contentResolver.openOutputStream(uri)?.use { os ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, os)
            os.flush()
        } ?: throw IOException("Failed to open output stream")
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val path = values.getAsString(MediaStore.Images.Media.DATA)
            if (path != null) {
                MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("image/jpeg"), null)
            }
        }

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
            FileLogger.e("CaptureProcessor", "Failed to write EXIF metadata", e)
        }
        
        if (finalBitmap != rotatedBitmap) finalBitmap.recycle()
        if (rotatedBitmap != bitmap) rotatedBitmap.recycle()
        return uri
    }

    fun saveHeifBitmap(
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
        
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotatedBitmap = if (rotationDegrees != 0) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
        
        val finalBitmap = if (wmConfig != null && wmConfig.type != "None") {
            WatermarkProcessor().applyWatermark(rotatedBitmap, wmConfig, characteristics, captureResult, 0, config)
        } else {
            rotatedBitmap
        }

        val fileName = createPhotoDisplayName(timestamp, "heic")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/heic")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + folderName + "/")
            } else {
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), folderName)
                if (!folder.exists()) folder.mkdirs()
                val file = File(folder, fileName)
                put(MediaStore.Images.Media.DATA, file.absolutePath)
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore entry")
        
        try {
            val exifBytes = extractExifBytes(finalBitmap.width, finalBitmap.height, characteristics, captureResult, location)
            
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val builder = HeifWriter.Builder(pfd.fileDescriptor, finalBitmap.width, finalBitmap.height, HeifWriter.INPUT_MODE_BITMAP)
                    .setQuality(quality)
                    .setMaxImages(1)
                
                val writer = builder.build()
                writer.start()
                writer.addBitmap(finalBitmap)
                
                if (exifBytes != null) {
                    writer.addExifData(0, exifBytes, 0, exifBytes.size)
                    FileLogger.d("CaptureProcessor", "EXIF data added to HEIF")
                }

                writer.stop(5000)
                
                try {
                    val closeMethod = writer.javaClass.getMethod("close")
                    closeMethod.invoke(writer)
                } catch (e: Exception) {
                    // Ignore close restriction
                }
            }
        } catch (e: Exception) {
            FileLogger.e("CaptureProcessor", "Failed to save HEIF image", e)
            contentResolver.delete(uri, null, null)
            throw e
        }
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val path = values.getAsString(MediaStore.Images.Media.DATA)
            if (path != null) {
                MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("image/heic"), null)
            }
        }
        
        if (finalBitmap != rotatedBitmap) finalBitmap.recycle()
        if (rotatedBitmap != bitmap) rotatedBitmap.recycle()
        return uri
    }

    private fun extractExifBytes(width: Int, height: Int, characteristics: CameraCharacteristics?, result: TotalCaptureResult?, location: android.location.Location?): ByteArray? {
        if (characteristics == null || result == null) return null
        
        val tempFile = File(context.cacheDir, "temp_exif_${System.currentTimeMillis()}.jpg")
        try {
            val dummy = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            FileOutputStream(tempFile).use { dummy.compress(Bitmap.CompressFormat.JPEG, 10, it) }
            dummy.recycle()
            
            val exif = ExifInterface(tempFile.absolutePath)
            writeExifMetadata(exif, characteristics, result)
            
            // Set actual dimensions to fix "1x1px" identification issue in gallery
            exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())

            if (preferenceManager.getBoolean("save_location", true)) {
                location?.let { exif.setGpsInfo(it) }
            }
            exif.saveAttributes()
            
            val bytes = tempFile.readBytes()
            var i = 0
            while (i < bytes.size - 3) {
                if ((bytes[i].toInt() and 0xFF) == 0xFF && (bytes[i+1].toInt() and 0xFF) == 0xE1) {
                    val length = ((bytes[i+2].toInt() and 0xFF) shl 8) or (bytes[i+3].toInt() and 0xFF)
                    val exifPayload = ByteArray(length - 2)
                    System.arraycopy(bytes, i + 4, exifPayload, 0, length - 2)
                    return exifPayload
                }
                i++
            }
        } catch (e: Exception) {
            FileLogger.e("CaptureProcessor", "Failed to extract EXIF bytes", e)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
        return null
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
        focalLength?.let { exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString()) }
        
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
