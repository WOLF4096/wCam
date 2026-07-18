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
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.io.IOException

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
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$folderName")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val folder = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
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

        val dngCreator = DngCreator(characteristics, captureResult).apply {
            setOrientation(exifOrientation)
            if (preferenceManager?.getBoolean("save_location", true) == true) {
                location?.let { setLocation(it) }
            }
        }
        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                // Direct saving without calculating strides or buffers manually
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
                // 加载稍大一点的缩略图以保证中心裁剪后的清晰度
                val source = context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
                
                source?.let {
                    // 计算中心 1:1 区域
                    val minDim = Math.min(it.width, it.height)
                    val left = (it.width - minDim) / 2
                    val top = (it.height - minDim) / 2
                    
                    // 裁剪中心 1:1 区域
                    val cropped = Bitmap.createBitmap(it, left, top, minDim, minDim)
                    
                    // 如果裁剪产生了新 Bitmap，则回收原始图以节省内存
                    if (cropped != it) {
                        it.recycle()
                    }
                    
                    // 压缩/缩放到预览按钮建议的尺寸 (256x256)
                    if (cropped.width > 256) {
                        val scaled = Bitmap.createScaledBitmap(cropped, 256, 256, true)
                        if (scaled != cropped) {
                            cropped.recycle()
                        }
                        scaled
                    } else {
                        cropped
                    }
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun findLatestRecentPhotoUri(): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val id = cursor.getLong(idColumn)
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }
}
