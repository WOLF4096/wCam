package com.example.wcam.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.MediaScannerConnection
import android.net.Uri
import com.example.wcam.util.FileLogger
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val RAW_MIME_TYPE = "image/x-adobe-dng"

class RawMediaStore(
    private val context: Context,
    private val config: com.example.wcam.util.ConfigManager? = null,
    private val preferenceManager: com.example.wcam.PreferenceManager? = null
) {
    fun saveRawImage(
        image: Image,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
        exifOrientation: Int,
        location: android.location.Location?,
        timestamp: Long = System.currentTimeMillis()
    ): Uri {
        val resolver = context.contentResolver
        val fileName = createPhotoDisplayName(timestamp, "dng")
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        var outputPathForScan: String? = null
        
        val folderName = config?.getString("default_storage_folder", "wCam") ?: "wCam"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, RAW_MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + folderName + "/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val folder = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    folderName
                )
                if (!folder.exists() && !folder.mkdirs()) {
                    throw IOException("Unable to create RAW output directory")
                }
                val outputFile = File(folder, fileName)
                outputPathForScan = outputFile.absolutePath
                put(MediaStore.Images.Media.DATA, outputFile.absolutePath)
            }
        }

        val uri = resolver.insert(collection, contentValues)
            ?: throw IOException("Unable to create MediaStore record")

        val saveStart = System.currentTimeMillis()
        val dngCreator = DngCreator(characteristics, captureResult).apply {
            setOrientation(exifOrientation)
            if (preferenceManager?.getBoolean("save_location", true) == true) {
                location?.let { setLocation(it) }
            }
        }
        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                dngCreator.writeImage(outputStream, image)
            } ?: throw IOException("Unable to open RAW output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publishValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, publishValues, null, null)
            } else if (outputPathForScan != null) {
                MediaScannerConnection.scanFile(context, arrayOf(outputPathForScan), arrayOf(RAW_MIME_TYPE), null)
            }
            android.util.Log.d("CaptureTimer", "[$timestamp] RAW save finished in ${System.currentTimeMillis() - saveStart}ms")
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw IOException("Unable to save RAW image", e)
        } finally {
            dngCreator.close()
        }
    }

    fun loadThumbnailBitmap(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val source = context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
                source?.let {
                    val minDim = Math.min(it.width, it.height)
                    val left = (it.width - minDim) / 2
                    val top = (it.height - minDim) / 2
                    val cropped = Bitmap.createBitmap(it, left, top, minDim, minDim)
                    if (cropped != it) it.recycle()
                    if (cropped.width > 256) {
                        val scaled = Bitmap.createScaledBitmap(cropped, 256, 256, true)
                        if (scaled != cropped) cropped.recycle()
                        scaled
                    } else cropped
                }
            } else null
        } catch (e: Exception) { null }
    }

    fun findLatestRecentPhotoUri(): Uri? {
        val folderName = config?.getString("default_storage_folder", "wCam") ?: "wCam"
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        
        // Filter by folder name
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ?"
        }
        val selectionArgs = arrayOf("%$folderName%")
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        
        val namePattern = Regex("""^\d{8}_\d{6}_\d{3}\..*$""")
        
        try {
            context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameColumn)
                    if (displayName == null || !namePattern.matches(displayName)) {
                        continue
                    }

                    val id = cursor.getLong(idColumn)
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    
                    // Verify if file still exists and is accessible
                    try {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use {
                            return uri
                        }
                    } catch (e: Exception) {
                        // File might be deleted, continue searching
                    }
                }
            }
        } catch (e: Exception) {
            FileLogger.e("RawMediaStore", "Failed to query latest photo in $folderName", e)
        }
        return null
    }

    private fun createPhotoDisplayName(timestampMillis: Long, extension: String): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        return "${formatter.format(Date(timestampMillis))}.$extension"
    }
}
