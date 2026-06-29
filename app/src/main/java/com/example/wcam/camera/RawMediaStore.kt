package com.example.wcam.camera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.io.IOException

private const val RAW_MIME_TYPE = "image/x-adobe-dng"
private const val RAW_FOLDER_NAME = "wCam"

class RawMediaStore(private val context: Context) {
    fun saveRawImage(
        image: Image,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
        exifOrientation: Int,
    ): Uri {
        val resolver = context.contentResolver
        val fileName = createPhotoDisplayName(extension = "dng")
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        var outputPathForScan: String? = null

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, RAW_MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$RAW_FOLDER_NAME")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val folder = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    RAW_FOLDER_NAME
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

        val dngCreator = DngCreator(characteristics, captureResult).apply {
            setOrientation(exifOrientation)
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
                context.contentResolver.loadThumbnail(uri, Size(160, 160), null)
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun findLatestRecentPhotoUri(): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH}=?"
            selectionArgs = arrayOf("${Environment.DIRECTORY_PICTURES}/$RAW_FOLDER_NAME/")
        } else {
            selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            selectionArgs = arrayOf("%/Pictures/$RAW_FOLDER_NAME/%")
        }

        return try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
