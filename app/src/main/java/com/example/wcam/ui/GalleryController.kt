package com.example.wcam.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.widget.TextView
import com.example.wcam.R
import com.example.wcam.camera.RawMediaStore
import java.util.concurrent.ExecutorService

/**
 * Manages gallery interaction and thumbnail updates.
 */
class GalleryController(
    private val context: Context,
    private val btnGallery: TextView,
    private val rawMediaStore: RawMediaStore,
    private val captureExecutor: ExecutorService,
    private val runOnUiThread: (Runnable) -> Unit
) {

    private var recentPhotoUri: Uri? = null

    fun updateRecentPhotoPreview(uri: Uri?) {
        recentPhotoUri = uri
        if (uri == null) {
            runOnUiThread {
                btnGallery.setBackgroundResource(R.drawable.bg_gallery_modern)
                btnGallery.text = "相册"
            }
            return
        }

        captureExecutor.submit {
            val thumbnail = rawMediaStore.loadThumbnailBitmap(uri)
            runOnUiThread {
                if (thumbnail != null) {
                    btnGallery.background = BitmapDrawable(context.resources, thumbnail)
                    btnGallery.text = ""
                } else {
                    btnGallery.setBackgroundResource(R.drawable.bg_gallery_modern)
                    btnGallery.text = "相册"
                }
            }
        }
    }

    fun openRecentPhotoInGallery() {
        val targetUri = recentPhotoUri ?: rawMediaStore.findLatestRecentPhotoUri()
        if (targetUri == null) {
            return
        }

        recentPhotoUri = targetUri
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(targetUri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Log error
        }
    }

    fun getRecentUri(): Uri? = recentPhotoUri
}
